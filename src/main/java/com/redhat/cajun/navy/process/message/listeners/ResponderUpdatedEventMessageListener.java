package com.redhat.cajun.navy.process.message.listeners;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.redhat.cajun.navy.process.message.model.Message;
import com.redhat.cajun.navy.process.message.model.ResponderUpdatedEvent;
import com.redhat.cajun.navy.process.tracing.KafkaTracingUtils;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import org.jbpm.services.api.ProcessService;
import org.jbpm.services.api.query.QueryService;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.internal.KieInternalServices;
import org.kie.internal.process.CorrelationKey;
import org.kie.internal.process.CorrelationKeyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ResponderUpdatedEventMessageListener {

    private static final Logger log = LoggerFactory.getLogger(ResponderUpdatedEventMessageListener.class);

    private final static String TYPE_RESPONDER_UPDATED_EVENT = "ResponderUpdatedEvent";

    private static final String SIGNAL_RESPONDER_AVAILABLE = "ResponderAvailable";

    @Autowired
    private ProcessService processService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Tracer tracer;

    private CorrelationKeyFactory correlationKeyFactory = KieInternalServices.Factory.get().newCorrelationKeyFactory();

    @KafkaListener(topics = "${listener.destination.responder-updated-event}")
    public void processMessage(@Payload String messageAsJson, @Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) String key,
                               @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                               @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
                               @Headers Map<String, Object> headers, Acknowledgment ack) {

        if (!accept(messageAsJson)) {
            ack.acknowledge();
            return;
        }

        log.debug("Processing '" + TYPE_RESPONDER_UPDATED_EVENT + "' message for responder '" + key + "' from topic:partition '" + topic + ":" + partition + "'");
        Span span = KafkaTracingUtils.buildChildSpan("processResponderUpdatedEvent", headers, tracer);
        try(Scope scope = tracer.activateSpan(span)) {
            doProcessMessage(messageAsJson, key, ack);
        } finally {
            span.finish();
        }

    }

    private void doProcessMessage(String messageAsJson, String responder, Acknowledgment ack) {

        Message<ResponderUpdatedEvent> message;
        try {

            message = new ObjectMapper().readValue(messageAsJson, new TypeReference<Message<ResponderUpdatedEvent>>() {});

            String incidentId = message.getHeaderValue("incidentId");
            if (incidentId == null || incidentId.isEmpty()) {
                log.warn("Message contains no header value for incidentId. Message cannot be processed!");
                ack.acknowledge();
                return;
            }

            CorrelationKey correlationKey = correlationKeyFactory.newCorrelationKey(incidentId);

            Boolean available = "success".equals(message.getBody().getStatus());

            log.debug("Signaling process with correlationkey '" + correlationKey + ". Responder '" + responder + "', available '" + available + "'." );
            final IntegerHolder holder = new IntegerHolder(5);
            while (holder.counting()) {
                TransactionTemplate template = new TransactionTemplate(transactionManager);
                template.execute((TransactionStatus s) -> {
                    // check if process is waiting on 'ResponderAvailable' signal
                    if (!SignalsByCorrelationKeyHelper.waitingForSignal(queryService, incidentId, "ResponderAvailable")) {
                        log.warn("Try " + holder.getValue() + " - Process instance with correlationKey '" + incidentId + "' is not waiting for signal 'ResponderAvailable'.");
                        holder.add();
                        return null;
                    }
                    holder.reset();
                    return null;
                });
                if (holder.limit()) {
                    log.warn("Process instance with correlationKey '" + incidentId + "' is not waiting for signal 'ResponderAvailable'. Process instance is not signaled.");
                } else if (holder.counting()) {
                    log.info("Sleeping for 300 ms");
                    Thread.sleep(300);
                }
            }
            if (holder.done()) {
                TransactionTemplate template = new TransactionTemplate(transactionManager);
                template.execute((TransactionStatus s) -> {
                    ProcessInstance instance = processService.getProcessInstance(correlationKey);
                    processService.signalProcessInstance(instance.getId(), SIGNAL_RESPONDER_AVAILABLE, available);
                    return null;
                });
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing msg " + messageAsJson, e);
            throw new IllegalStateException(e.getMessage(), e);
        }

    }

    private boolean accept(String messageAsJson) {
        try {
            String messageType = JsonPath.read(messageAsJson, "$.messageType");
            if (TYPE_RESPONDER_UPDATED_EVENT.equalsIgnoreCase(messageType) ) {
                return true;
            } else {
                log.debug("Message with type '" + messageType + "' is ignored");
            }
        } catch (Exception e) {
            log.warn("Unexpected message without 'messageType' field.");
        }
        return false;
    }

    public static class IntegerHolder {

        private int value;

        private int limit;

        public IntegerHolder(int limit) {
            value = 1;
            this.limit = limit;
        }

        public void add() {
            value++;
        }

        public int getValue() {
            return value;
        }

        public void reset() {
            value = 0;
        }

        public boolean limit() {
            return value > limit;
        }

        public boolean counting() {
            return value > 0 && value <= limit;
        }

        public boolean done() {
            return value == 0;
        }

    }
}
