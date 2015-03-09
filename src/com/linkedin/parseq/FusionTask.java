package com.linkedin.parseq;

import java.util.Optional;

import com.linkedin.parseq.function.Consumer1;
import com.linkedin.parseq.function.Function1;
import com.linkedin.parseq.function.Try;
import com.linkedin.parseq.internal.ArgumentUtil;
import com.linkedin.parseq.promise.Promise;
import com.linkedin.parseq.promise.PromisePropagator;
import com.linkedin.parseq.promise.PromiseResolvedException;
import com.linkedin.parseq.promise.Promises;
import com.linkedin.parseq.promise.Settable;
import com.linkedin.parseq.promise.SettablePromise;
import com.linkedin.parseq.trace.ShallowTrace;
import com.linkedin.parseq.trace.ShallowTraceBuilder;

/**
 *
 *
 * @author jodzga
 *
 * @param <S>
 * @param <T>
 */
public class FusionTask<S, T>  extends BaseTask<T> {

  private static final String FUSION_TRACE_SYMBOL = " => ";

  private final PromisePropagator<S, T> _propagator;
  private final Task<S> _task;
  private final Promise<S> _source;
  private final String _description;
  private final boolean _systemHidden;

  private FusionTask(final String desc, final Task<S> task, final Promise<S> source,
      final PromisePropagator<S, T> propagator, final boolean systemHidden) {
    super(task != null ? task.getName() + FUSION_TRACE_SYMBOL + desc : desc);
    _description = desc;
    _propagator = propagator;
    _task = task;
    _source = source;
    _systemHidden = systemHidden;
  }

  private PromisePropagator<S, T> fulfilling(final PromisePropagator<S, T> propagator) {
    return (src, dest) -> {
      propagator.accept(src, new Settable<T>() {

        @Override
        public void done(final T value) throws PromiseResolvedException {
          try {
            final SettablePromise<T> settable = FusionTask.this.getSettableDelegate();
            if (!settable.isDone()) {
              settable.done(value);
            }
          } finally {
            dest.done(value);
          }
        }

        @Override
        public void fail(final Throwable error) throws PromiseResolvedException {
          try {
            final SettablePromise<T> settable = FusionTask.this.getSettableDelegate();
            if (!settable.isDone()) {
              settable.fail(error);
            }
          } finally {
            dest.fail(error);
          }
        }
      });
    };
  }

  @SuppressWarnings("unchecked")
  public static <S, T> FusionTask<?, T> fuse(final String name, final Task<S> task, final PromisePropagator<S, T> propagator) {
    if (task instanceof FusionTask) {
      return ((FusionTask<?, S>)task).apply(name, propagator);
    } else {
      return new FusionTask<S, T>(name, task, task, propagator, true);
    }
  }

  public static <S, T> FusionTask<?, T> create(final String name, final Promise<S> source, final PromisePropagator<S, T> propagator) {
    return new FusionTask<S, T>(name, null, source, propagator, false);
  }

  @Override
  public <R> FusionTask<?, R> apply(String desc, PromisePropagator<T,R> propagator) {
    return fuse(desc, _task, fulfilling(_propagator).compose(propagator));
  };

  @Override
  public <R> Task<R> map(final String desc, final Function1<? super T,? extends R> f) {
    return super.map(_description + FUSION_TRACE_SYMBOL + desc, f);
  }

  @Override
  public Task<T> andThen(final String desc, final Consumer1<? super T> consumer) {
    return super.andThen(_description + FUSION_TRACE_SYMBOL + desc, consumer);
  }

  @Override
  public Task<T> recover(final String desc, final Function1<Throwable, T> f) {
    return super.recover(_description + FUSION_TRACE_SYMBOL + desc, f);
  }

  @Override
  public Task<T> recoverWith(final String desc, final Function1<Throwable, Task<T>> func) {
    ArgumentUtil.requireNotNull(func, "function");
    final Task<T> that = this;
    return Task.async(desc, context -> {
      final SettablePromise<T> result = Promises.settable();
      context.after(that).run(() -> {
        if (that.isFailed() && !(that.getError() instanceof EarlyFinishException)) {
          try {
            Task<T> r = func.apply(that.getError());
            Promises.propagateResult(r, result);
            return Optional.of(r);
          } catch (Throwable t) {
            result.fail(t);
            return Optional.empty();
          }
        } else {
          result.done(that.get());
          return Optional.empty();
        }
      });
      context.run(that);
      return result;
    }, true);
  }

  @Override
  public Task<T> onFailure(final String desc, final Consumer1<Throwable> consumer) {
    return super.onFailure(_description + FUSION_TRACE_SYMBOL + desc, consumer);
  }

  @Override
  public Task<Try<T>> withTry(final String desc) {
    return super.withTry(_description + FUSION_TRACE_SYMBOL + desc);
  }

  protected SettablePromise<T> propagate(SettablePromise<T> result) {
    try {
      _propagator.accept(_source, result);
    } catch (Throwable t) {
      result.fail(t);
    }
    return result;
  }

  @Override
  protected Promise<? extends T> run(Context context) throws Throwable {
    final SettablePromise<T> result = Promises.settable();
    if (_task == null) {
      propagate(result);
    } else {
      final Task<? extends T> propagationTask =
          Task.async(_description, () -> propagate(result), false);
      context.after(_task).run(propagationTask);
      context.run(_task);
    }
    return result;
  }

  @Override
  public ShallowTrace getShallowTrace() {
    return new ShallowTraceBuilder(super.getShallowTrace()).setSystemHidden(_systemHidden).build();
  }

}
