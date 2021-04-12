/*
 * MIT License
 *
 * Copyright (c) 2021 Hasan Demirtaş
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package io.github.portlek.synergy.core.transaction;

import io.github.portlek.synergy.api.TransactionInfo;
import io.github.portlek.synergy.api.TransactionManager;
import io.github.portlek.synergy.core.Synergy;
import io.github.portlek.synergy.core.config.SynergyConfig;
import io.github.portlek.synergy.languages.Languages;
import io.github.portlek.synergy.proto.Commands;
import io.github.portlek.synergy.proto.Protocol;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.log4j.Log4j2;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * a simple implementation of {@link TransactionManager}.
 */
@Log4j2
public final class SimpleTransactionManager implements TransactionManager {

  /**
   * the transactions.
   */
  private final Map<String, TransactionInfo> transactions = new ConcurrentHashMap<>();

  @NotNull
  @Override
  public Optional<Protocol.Transaction> build(@NotNull final String id, @NotNull final Protocol.Transaction.Mode mode,
                                              @NotNull final Commands.BaseCommand command) {
    final var info = this.getTransactionInfo(id);
    if (info.isEmpty()) {
      SimpleTransactionManager.log.error(Languages.getLanguageValue("unable-to-build-transaction", id));
      return Optional.empty();
    }
    return Optional.of(Protocol.Transaction.newBuilder()
      .setId(id)
      .setMode(mode)
      .setPayload(command)
      .build());
  }

  @Override
  public boolean cancel(@NotNull final String id, final boolean silentFail) {
    final var optional = this.getTransactionInfo(id);
    if (optional.isEmpty()) {
      if (!silentFail) {
        SimpleTransactionManager.log.error(Languages.getLanguageValue("cannot-cancel-transaction", id));
      }
      return false;
    }
    final var info = optional.get();
    info.getListener().ifPresent(listener -> listener.onCancel(this, info));
    final var cancelTask = info.getCancelTask();
    if (cancelTask.isPresent() && !cancelTask.get().isDone()) {
      cancelTask.get().cancel(false);
    }
    info.setDone(true);
    this.transactions.remove(id);
    return true;
  }

  @Override
  public boolean complete(@NotNull final String id) {
    final var optional = this.getTransactionInfo(id);
    if (optional.isEmpty()) {
      SimpleTransactionManager.log.error(Languages.getLanguageValue("cannot-complete-transaction", id));
      return false;
    }
    final var info = optional.get();
    info.getListener().ifPresent(listener -> listener.onComplete(this, info));
    final var cancelTask = info.getCancelTask();
    if (cancelTask.isPresent() && !cancelTask.get().isDone()) {
      cancelTask.get().cancel(false);
    }
    info.setDone(true);
    this.transactions.remove(id);
    return true;
  }

  @NotNull
  @Override
  public TransactionInfo generateInfo() {
    final var info = new SimpleTransactionInfo();
    final var generatedId = Synergy.getInstance().generateId();
    info.setId(generatedId);
    this.transactions.put(generatedId, info);
    info.setCancelTask(Synergy.getInstance().getScheduler().schedule(() -> {
      SimpleTransactionManager.log.warn(Languages.getLanguageValue("transaction-cancelled", generatedId));
      return this.cancel(generatedId, true);
    }, SynergyConfig.transactionTimeout, TimeUnit.SECONDS));
    return info;
  }

  @NotNull
  @Override
  public Optional<TransactionInfo> getTransactionInfo(@NotNull final String id) {
    return Optional.ofNullable(this.transactions.get(id));
  }

  @Override
  public void receive(@NotNull final Protocol.Transaction message, @NotNull final String from) {
    final Optional<TransactionInfo> transactionInfo;
    switch (message.getMode()) {
      case CREATE:
        if (this.transactions.containsKey(message.getId())) {
          SimpleTransactionManager.log.error(Languages.getLanguageValue("received-create-already-exists"),
            message.getId());
          return;
        }
        final var info0 = new SimpleTransactionInfo();
        info0.setId(message.getId());
        info0.setTarget(from);
        this.transactions.put(message.getId(), info0);
        transactionInfo = Optional.of(info0);
        break;
      case SINGLE:
        final var info1 = new SimpleTransactionInfo();
        info1.setDone(true);
        transactionInfo = Optional.of(info1);
        break;
      case CONTINUE:
        final var info2 = this.getTransactionInfo(message.getId());
        if (info2.isEmpty()) {
          SimpleTransactionManager.log.error(Languages.getLanguageValue("received-continue-does-not-exist",
            message.getId()));
          return;
        }
        final var info3 = info2.get();
        info3.getListener().ifPresent(listener -> listener.onReceive(this, info3, message));
        transactionInfo = Optional.of(info3);
        break;
      case COMPLETE:
        final var info4 = this.getTransactionInfo(message.getId());
        if (info4.isEmpty()) {
          SimpleTransactionManager.log.error(Languages.getLanguageValue("received-complete-does-not-exist"),
            message.getId());
          return;
        }
        final var info5 = info4.get();
        info5.getListener().ifPresent(listener -> listener.onReceive(this, info5, message));
        info5.setDone(true);
        if (info5.getId().isEmpty() || !this.complete(info5.getId().get())) {
          SimpleTransactionManager.log.error(Languages.getLanguageValue("unable-to-complete-transaction"),
            info5.getId().orElse(null));
          return;
        }
        transactionInfo = Optional.of(info5);
        break;
      default:
        transactionInfo = Optional.empty();
        break;
    }
    transactionInfo.ifPresent(info ->
      Synergy.getInstance().process(message.getPayload(), info, from));
  }

  @Override
  public boolean send(@NotNull final String id, @NotNull final Protocol.Transaction message,
                      @Nullable final String target) {
    final var optional = this.getTransactionInfo(id);
    if (optional.isEmpty()) {
      SimpleTransactionManager.log.error(Languages.getLanguageValue("cannot-send-transaction", id));
      return false;
    }
    final var info = optional.get();
    if (!id.equals(message.getId())) {
      SimpleTransactionManager.log.error(Languages.getLanguageValue("message-id-does-not-match", id));
      return false;
    }
    info.setTransaction(message);
    info.setTarget(target);
    info.getListener().ifPresent(listener -> listener.onSend(this, info));
    final var mode = message.getMode();
    if (info.getId().isPresent() &&
      (mode == Protocol.Transaction.Mode.COMPLETE || mode == Protocol.Transaction.Mode.SINGLE)) {
      this.complete(info.getId().get());
    }
    return Synergy.getInstance().send(message, info.getTarget().orElse(null));
  }
}
