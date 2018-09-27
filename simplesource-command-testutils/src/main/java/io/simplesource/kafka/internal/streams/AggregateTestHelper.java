package io.simplesource.kafka.internal.streams;

import io.simplesource.api.CommandAPI;
import io.simplesource.api.CommandAPI.CommandError;
import io.simplesource.data.Sequence;
import io.simplesource.data.NonEmptyList;
import io.simplesource.data.Reason;
import io.simplesource.data.Result;
import io.simplesource.kafka.model.AggregateUpdate;
import io.simplesource.kafka.model.AggregateUpdateResult;
import io.simplesource.kafka.model.ValueWithSequence;
import org.apache.kafka.streams.KeyValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public final class AggregateTestHelper<K, C, E, A> {
    private final AggregateTestDriver<K, C, E, A> testAPI;

    public AggregateTestHelper(final AggregateTestDriver<K, C, E, A> testAPI) {
        this.testAPI = testAPI;
    }

    public PublishBuilder publishCommand(
        final K key,
        final Sequence readSequence,
        final C command) {
        return new PublishBuilder(key, readSequence, command);
    }

    private UUID publish(
        final K key,
        final Sequence readSequence,
        final C command) {
        final UUID commandId = UUID.randomUUID();
        final Result<CommandError, UUID> result = testAPI.publishCommand(new CommandAPI.Request<>(key, readSequence, commandId, command))
            .unsafePerform(AggregateTestHelper::commandError);
        return result.fold(
            reasons -> fail("Publishing command " + command + " failed with " + reasons),
            uuid -> {
                assertEquals(uuid, commandId);
                return uuid;
            }
        );
    }

    private PublishResponse publishExpectingSuccess(
        final K key,
        final Sequence readSequence,
        final C command,
        final NonEmptyList<E> expectedEvents,
        final A expectedAggregate
    ) {
        final UUID commandId = publish(key, readSequence, command);
        final NonEmptyList<Sequence> expectedSequences = validateEvents(key, readSequence, expectedEvents);

        final AggregateUpdateResult<A> updateResponse = testAPI.fetchAggregateUpdateResult(commandId)
            .orElseGet(() -> fail("Didn't find command response"));
        assertEquals(commandId, updateResponse.commandId());
        assertEquals(readSequence, updateResponse.readSequence());
        final AggregateUpdate<A> aggregateUpdate = updateResponse.updatedAggregateResult().fold(
            reasons -> fail("Updating aggregate_update failed for command " + command + " failed with " + reasons),
            Function.identity()
        );
        assertEquals(expectedSequences.last(), aggregateUpdate.sequence());
        assertEquals(expectedAggregate, aggregateUpdate.aggregate());

        final KeyValue<K, AggregateUpdate<A>> aggregateUpdatePair = testAPI.readAggregateTopic()
            .orElseGet(() -> fail("Missing update on aggregate_update topic"));
        assertEquals(key, aggregateUpdatePair.key);
        assertEquals(expectedSequences.last(), aggregateUpdatePair.value.sequence());
        assertEquals(aggregateUpdate.aggregate(), aggregateUpdatePair.value.aggregate());


        final Result<CommandError, NonEmptyList<Sequence>> queryByCommandId = testAPI
            .queryCommandResult(commandId, Duration.ofHours(1))
            .unsafePerform(AggregateTestHelper::commandError);
        queryByCommandId.fold(
            reasons -> fail("Failed to fetch result with commandId " + reasons),
            sequences -> {
                assertEquals(expectedSequences, sequences);
                return null;
            });

        return new PublishResponse(key, aggregateUpdate);
    }

    private void publishExpectingError(
        final K key,
        final Sequence readSequence,
        final C command,
        final Consumer<NonEmptyList<Reason<CommandError>>> failureValidator
    ) {
        final UUID commandId = publish(key, readSequence, command);

        final AggregateUpdateResult<A> updateResponse = testAPI.fetchAggregateUpdateResult(commandId)
            .orElseGet(() -> fail("Didn't find command response"));
        assertEquals(commandId, updateResponse.commandId());
        assertEquals(readSequence, updateResponse.readSequence());
        updateResponse.updatedAggregateResult().fold(
            reasons -> {
                failureValidator.accept(reasons);
                return null;
            },
            aggregateUpdate -> fail("Expected update failure for command " + command + " but got update " + aggregateUpdate));

        assertEquals(Optional.empty(), testAPI.readEventTopic());
        assertEquals(Optional.empty(), testAPI.readAggregateTopic());

        final Result<CommandError, NonEmptyList<Sequence>> queryByCommandId = testAPI
            .queryCommandResult(commandId, Duration.ofHours(1))
            .unsafePerform(AggregateTestHelper::commandError);
        queryByCommandId.fold(
            reasons -> {
                failureValidator.accept(reasons);
                return null;
            },
            aggregateUpdate -> fail("Expected update failure for command " + command + " but got update " + aggregateUpdate));
    }

    private NonEmptyList<Sequence> validateEvents(final K key,
        final Sequence readSequence,
        final NonEmptyList<E> expectedEvents) {
        final Sequence head = validEvent(key, new ValueWithSequence<>(
            expectedEvents.head(),
            readSequence.next()));
        final List<Sequence> tail = new ArrayList<>();

        Sequence expectedWriteSequence = head.next();
        for (final E expectedEvent: expectedEvents.tail()) {
            tail.add(validEvent(key, new ValueWithSequence<>(expectedEvent, expectedWriteSequence)));
            expectedWriteSequence = expectedWriteSequence.next(); // should go up with each event
        }
        return new NonEmptyList<>(head, tail);
    }

    private Sequence validEvent(final K key, final ValueWithSequence<E> expectedValue) {
        final KeyValue<K, ValueWithSequence<E>> eventPair = testAPI.readEventTopic()
            .orElseGet(() -> fail("Missing update on event topic. Expected " + expectedValue));
        assertEquals(key, eventPair.key);
        assertEquals(expectedValue.sequence(), eventPair.value.sequence());
        assertEquals(expectedValue.value(), eventPair.value.value());
        return expectedValue.sequence();
    }

    private static CommandError commandError(final Exception e) {
        return CommandError.InternalError;
    }



    public final class PublishBuilder {
        private final K key;
        private final Sequence readSequence;
        private final C command;

        PublishBuilder(final K key, final Sequence readSequence, final C command) {
            this.key = key;
            this.readSequence = readSequence;
            this.command = command;
        }

        /**
         * Publish the command and assert that the publishCommand was success generating the given events and aggregate_update update.
         */
        public PublishResponse expecting(
            final NonEmptyList<E> expectedEvents,
            final A expectedAggregate) {
            return publishExpectingSuccess(key, readSequence, command, expectedEvents, expectedAggregate);
        }

        /**
         * Publish the command and assert that the call failed.
         *
         * @param failureValidator the failure reasons are provided for validation
         */
        public void expectingFailure(
            final Consumer<NonEmptyList<Reason<CommandError>>> failureValidator) {
            publishExpectingError(key, readSequence, command, failureValidator);
        }

        /**
         * Publish the command and assert that the call failed.
         *
         * @param expectedErrorCodes expected error codes contains in each of the generated failure reasons
         */
        public void expectingFailure(
            final NonEmptyList<CommandError> expectedErrorCodes) {
            final Consumer<NonEmptyList<Reason<CommandError>>> failureValidator = reasons ->
                assertEquals(expectedErrorCodes, reasons.map(Reason::getError));
            expectingFailure(failureValidator);
        }
    }

    public final class PublishResponse {
        private final K key;
        private final AggregateUpdate<A> aggregateUpdate;

        PublishResponse(final K key, final AggregateUpdate<A> aggregateUpdate) {
            this.key = key;
            this.aggregateUpdate = aggregateUpdate;
        }

        /**
         * Combinator to chain together multiple publishCommand commands.
         * Uses sequence from most recent event.
         *
         * @param command the next command to publishCommand
         */
        public PublishBuilder thenPublish(final C command) {
            return new PublishBuilder(key, aggregateUpdate.sequence(), command);
        }

        /**
         * Combinator to chain together multiple publishCommand commands.
         * Use this version if you need access to the current project, or if you want to mess with the sequence.
         *
         * @param commandGenerator a generate a new command and sequence from the latest project value and sequence
         */
        public PublishBuilder thenPublish(final Function<AggregateUpdate<A>, ValueWithSequence<C>> commandGenerator) {
                final ValueWithSequence<C> commandWithSequence = commandGenerator.apply(aggregateUpdate);
                return new PublishBuilder(key, commandWithSequence.sequence(), commandWithSequence.value());
        }
    }
}