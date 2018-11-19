package io.simplesource.kafka.internal.streams.topology;

import io.simplesource.kafka.api.AggregateResources;
import io.simplesource.kafka.internal.util.Tuple2;
import io.simplesource.kafka.model.*;
import lombok.Value;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;

import java.util.UUID;

public final class EventSourcedTopology {

    @Value
    static final class InputStreams<K, C> {
        public final KStream<K, CommandRequest<K, C>> commandRequest;
        public final KStream<K, CommandResponse> commandResponse;
    }

    public static <K, C, E, A> InputStreams<K, C> addTopology(TopologyContext<K, C, E, A> ctx, final StreamsBuilder builder) {
        // Create stores
        EventSourcedStores.addStateStores(ctx, builder);

        // Consume from topics
        final KStream<K, CommandRequest<K, C>> commandRequestStream = EventSourcedConsumer.commandRequestStream(ctx, builder);
        final KStream<K, CommandResponse> commandResponseStream = EventSourcedConsumer.commandResponseStream(ctx, builder);
        DistributorContext<CommandResponse> distCtx = getDistributorContext(ctx);
        final KStream<UUID, String> resultsTopicMapStream = ResultDistributor.resultTopicMapStream(distCtx,  builder);

        // Handle idempotence by splitting stream into processed and unprocessed
        Tuple2<KStream<K, CommandRequest<K, C>>, KStream<K, CommandResponse>> reqResp = EventSourcedStreams.getProcessedCommands(
                ctx, commandRequestStream, commandResponseStream);
        KStream<K, CommandRequest<K, C>> unprocessedRequests = reqResp.v1();
        KStream<K, CommandResponse> processedResponses = reqResp.v2();
        
        // Transformations
        final KStream<K, CommandEvents<E, A>> eventResultStream = EventSourcedStreams.eventResultStream(ctx, unprocessedRequests);
        KStream<K, ValueWithSequence<E>> eventsWithSequence = EventSourcedStreams.getEventsWithSequence(eventResultStream);

        final KStream<K, AggregateUpdateResult<A>> aggregateUpdateResults = EventSourcedStreams.getAggregateUpdateResults(ctx, eventResultStream);
        final KStream<K, AggregateUpdate<A>> aggregateUpdates = EventSourcedStreams.getAggregateUpdates(aggregateUpdateResults);
        final KStream<K, CommandResponse>commandResponses = EventSourcedStreams.getCommandResponses(aggregateUpdateResults);

        // Produce to topics
        EventSourcedPublisher.publishEvents(ctx, eventsWithSequence);
        EventSourcedPublisher.publishAggregateUpdates(ctx, aggregateUpdates);
        EventSourcedPublisher.publishCommandResponses(ctx, processedResponses);
        EventSourcedPublisher.publishCommandResponses(ctx, commandResponses);

        // Update stores
        EventSourcedStores.updateAggregateStateStore(ctx, aggregateUpdateResults);
        EventSourcedStores.updateCommandResponseStore(ctx, commandResponses);

        // Distribute command results
        ResultDistributor.distribute(distCtx, commandResponseStream, resultsTopicMapStream);

        // return input streams
        return new InputStreams<>(commandRequestStream, commandResponseStream);
    }

    private static  DistributorContext<CommandResponse> getDistributorContext(TopologyContext<?, ?, ?, ?> ctx) {
        return new DistributorContext<>(
                ctx.aggregateSpec().serialization().resourceNamingStrategy().topicName(ctx.aggregateSpec().aggregateName(), AggregateResources.TopicEntity.command_response_topic_map.toString()),
                new DistributorSerdes<>(ctx.serdes().commandResponseKey(), ctx.serdes().commandResponse()),
                ctx.aggregateSpec().generation().stateStoreSpec(),
                CommandResponse::commandId);
    }
}


