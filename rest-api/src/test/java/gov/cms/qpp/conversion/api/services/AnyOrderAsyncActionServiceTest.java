package gov.cms.qpp.conversion.api.services;

import gov.cms.qpp.conversion.api.exceptions.UncheckedInterruptedException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.Is.isA;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.internal.matchers.ThrowableCauseMatcher.hasCause;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@RunWith(MockitoJUnitRunner.class)
public class AnyOrderAsyncActionServiceTest {

	@InjectMocks
	private TestAnyOrderService objectUnderTest;

	@Mock
	private TaskExecutor taskExecutor;

	@Before
	public void runBeforeEachTest() throws InterruptedException {
		doAnswer(invocationOnMock -> {
			Runnable method = invocationOnMock.getArgument(0);
			CompletableFuture.runAsync(method);
			return null;
		}).when(taskExecutor).execute(any(Runnable.class));
	}

	@After
	public void runAfterEachTest() {
		objectUnderTest.pauseAsynchronousAction.set(false);
	}

	@Test
	public void testAsynchronousActionIsCalled() {
		runSimpleScenario(0);

		assertTrue("The asynchronousAction was not called.", objectUnderTest.asynchronousActionCalled.get());
	}

	@Test
	public void testObjectToActOnPassedDown() {
		Object objectToActOn = runSimpleScenario(0);

		assertThat("The object to act on didn't make it down to asynchronousAction.",
				objectUnderTest.objectThatWasActedOn.get(),
			is(objectToActOn));
	}

	@Test
	public void testSuccessNoRetry() {
		runSimpleScenario(0);

		assertThat("The asynchronousAction method was not called once and only once.",
				objectUnderTest.timesAsynchronousActionCalled.get(), is(1));
	}

	@Test
	public void testFailureRetry() {
		int failuresUntilSuccess = 3;

		runSimpleScenario(failuresUntilSuccess);

		assertThat("The asynchronousAction method was not called enough times.",
				objectUnderTest.timesAsynchronousActionCalled.get(),
			is(failuresUntilSuccess + 1));
	}

	@Test
	public void testObjectToActOnPassedDownWithFailures() {
		Object objectToActOn = runSimpleScenario(2);

		assertThat("The object to act on didn't make it down to asynchronousAction.",
				objectUnderTest.objectThatWasActedOn.get(),
			is(objectToActOn));
	}

	@Test
	public void testMultipleActsResultInAsynchronousActionsSuccess() {
		int numberOfItemsToProcess = 3;

		objectUnderTest.failuresUntilSuccess(0);

		List<CompletableFuture<Object>> completableFutures = new ArrayList<>();
		for (int currentItemIndex = 0; currentItemIndex < numberOfItemsToProcess; currentItemIndex++) {
			completableFutures.add(objectUnderTest.actOnItem(new Object()));
		}

		CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture<?>[0])).join();

		assertThat("The asynchronousAction method was not called as many times as it should have.",
				objectUnderTest.timesAsynchronousActionCalled.get(), is(numberOfItemsToProcess));
	}

	@Test
	public void testMultipleActsResultInAsynchronousActionsFailure() {
		int failuresUntilSuccess = 2;
		int numberOfItemsToProcess = 3;

		objectUnderTest.failuresUntilSuccess(failuresUntilSuccess);

		List<CompletableFuture<Object>> completableFutures = new ArrayList<>();
		for (int currentItemIndex = 0; currentItemIndex < numberOfItemsToProcess; currentItemIndex++) {
			completableFutures.add(objectUnderTest.actOnItem(new Object()));
		}

		CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture<?>[0])).join();

		assertThat("The asynchronousAction method was not called as many times as it should have.",
				objectUnderTest.timesAsynchronousActionCalled.get(), is((failuresUntilSuccess + 1) * numberOfItemsToProcess));
	}

	@Test
	public void testDependencyOrder() {
		objectUnderTest.failuresUntilSuccess(0);
		objectUnderTest.pauseAsynchronousAction.set(true);

		CompletableFuture<Object> completableFuture1 = objectUnderTest.actOnItem(new Object());
		CompletableFuture<Object> completableFuture2 = objectUnderTest.actOnItem(new Object());

		assertThat("No other CompletableFuture should be dependent on this one but there is.",
			completableFuture1.getNumberOfDependents(), is(0));
		assertThat("No other CompletableFuture should be dependent on this one but there is.",
			completableFuture2.getNumberOfDependents(), is(0));
	}

	@Test
	public void testInterruptedException() {

		objectUnderTest.failuresUntilSuccess(1).failWithInterruptException();

		CompletableFuture<Object> completableFuture = objectUnderTest.actOnItem(new Object());

		try {
			completableFuture.join();
			fail("A CompletionException was not thrown.");
		} catch (CompletionException exception) {
			assertThat("The CompletionException didn't contain a UncheckedInterruptedException.",
					exception, hasCause(isA(UncheckedInterruptedException.class)));
			assertThat("The asynchronousAction method should have been called only once.",
					objectUnderTest.timesAsynchronousActionCalled.get(), is(1));  //not two
		} catch (Exception exception) {
			fail("A CompletionException was not thrown.");
		}
	}

	private Object runSimpleScenario(int failuresUntilSuccess) {
		Object objectToActOn = new Object();

		objectUnderTest.failuresUntilSuccess(failuresUntilSuccess);
		CompletableFuture<Object> completableFuture = objectUnderTest.actOnItem(objectToActOn);

		completableFuture.join();

		return objectToActOn;
	}

	private static class TestAnyOrderService extends AnyOrderAsyncActionService<Object, Object> {
		public AtomicBoolean asynchronousActionCalled = new AtomicBoolean(false);
		public AtomicInteger timesAsynchronousActionCalled = new AtomicInteger(0);
		public AtomicReference<Object> objectThatWasActedOn = new AtomicReference<>(null);
		public AtomicBoolean pauseAsynchronousAction = new AtomicBoolean(false);

		private int failuresUntilSuccessTemplate = -1;
		private ThreadLocal<Integer> failuresUntilSuccess = ThreadLocal.withInitial(() -> -1);
		private ThreadLocal<Boolean> failWithInterruptException = ThreadLocal.withInitial(() -> Boolean.FALSE);

		public TestAnyOrderService failuresUntilSuccess(int failuresUntilSuccess) {
			this.failuresUntilSuccessTemplate = failuresUntilSuccess;
			this.failuresUntilSuccess = ThreadLocal.withInitial(() -> this.failuresUntilSuccessTemplate);
			return this;
		}

		public TestAnyOrderService failWithInterruptException() {
			this.failWithInterruptException = ThreadLocal.withInitial(() -> Boolean.TRUE);
			return this;
		}

		@Override
		protected Object asynchronousAction(final Object objectToActOn) {
			asynchronousActionCalled.set(true);
			timesAsynchronousActionCalled.incrementAndGet();
			objectThatWasActedOn.set(objectToActOn);

			while (pauseAsynchronousAction.get()) {
				try {
					Thread.sleep(100);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					pauseAsynchronousAction.set(false);
				}
			}

			if(failuresUntilSuccess.get() != 0) {
				if(failuresUntilSuccess.get() != -1) {
					failuresUntilSuccess.set(failuresUntilSuccess.get() - 1);
				}
				if(failWithInterruptException.get()) {
					throw new UncheckedInterruptedException(new InterruptedException());
				}
				else {
					throw new RuntimeException();
				}
			}

			failuresUntilSuccess.set(failuresUntilSuccessTemplate);

			return new Object();
		}

		@Override
		protected RetryTemplate retryTemplate() {
			RetryTemplate retry = super.retryTemplate();

			FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
			backOffPolicy.setBackOffPeriod(0);
			retry.setBackOffPolicy(backOffPolicy);

			return retry;
		}
	}
}