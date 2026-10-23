package com.caseware.fanout.aws;

import com.caseware.fanout.worker.Job;
import com.caseware.fanout.worker.Ports.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

/** JobSource over SQS. The worker owns the attempt count (message attribute), not ReceiveCount. */
public class SqsJobSource implements JobSource {
    private static final String ATTEMPT = "attempt";
    private final SqsClient sqs;
    private final String queueUrl;
    private final String dlqUrl;

    public SqsJobSource(SqsClient sqs, String queueUrl, String dlqUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.dlqUrl = dlqUrl;
    }

    public void send(Job j) {
        sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody(encode(j)));
    }

    /** Publish fan-out: 10 per SQS batch call. */
    public void sendAll(List<Job> jobs) {
        for (int i = 0; i < jobs.size(); i += 10) {
            var entries = new ArrayList<SendMessageBatchRequestEntry>();
            for (int k = i; k < Math.min(i + 10, jobs.size()); k++)
                entries.add(SendMessageBatchRequestEntry.builder().id(String.valueOf(k)).messageBody(encode(jobs.get(k))).build());
            var r = sqs.sendMessageBatch(b -> b.queueUrl(queueUrl).entries(entries));
            if (!r.failed().isEmpty()) throw new IllegalStateException("SQS batch failed: " + r.failed().get(0).message());
        }
    }

    @Override
    public List<Received> poll(int max) {
        List<Received> out = new ArrayList<>();
        while (out.size() < max) {
            int n = Math.min(10, max - out.size());
            int wait = out.isEmpty() ? 1 : 0;
            List<Message> ms = sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(n)
                    .waitTimeSeconds(wait).messageAttributeNames(ATTEMPT)).messages();
            for (Message m : ms) {
                var a = m.messageAttributes().get(ATTEMPT);
                out.add(new Received(decode(m.body()), a == null ? 1 : Integer.parseInt(a.stringValue()), m));
            }
            if (ms.isEmpty()) break;
        }
        return out;
    }

    @Override
    public void ack(Received r) {
        delete(r);
    }

    @Override
    public void defer(Received r, Duration delay) {
        // ponytail: visibility max is 12h, delays here are seconds
        sqs.changeMessageVisibility(b -> b.queueUrl(queueUrl).receiptHandle(msg(r).receiptHandle())
                .visibilityTimeout((int) Math.max(0, Math.min(delay.toSeconds(), 43200))));
    }

    @Override
    public void retry(Received r, Duration delay) {
        // send-then-delete: a crash between them duplicates the job (idempotency absorbs it), never loses it
        sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody(msg(r).body())
                .delaySeconds((int) Math.max(0, Math.min(delay.toSeconds(), 900)))
                .messageAttributes(Map.of(ATTEMPT, MessageAttributeValue.builder().dataType("Number")
                        .stringValue(String.valueOf(r.attempt() + 1)).build())));
        delete(r);
    }

    @Override
    public void deadLetter(Received r, String reason) {
        sqs.sendMessage(b -> b.queueUrl(dlqUrl).messageBody(msg(r).body())
                .messageAttributes(Map.of("reason", MessageAttributeValue.builder().dataType("String")
                        .stringValue(reason == null ? "" : reason).build())));
        delete(r);
    }

    private void delete(Received r) {
        sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(msg(r).receiptHandle()));
    }

    private static Message msg(Received r) {
        return (Message) r.handle();
    }

    static String encode(Job j) {
        return String.join("|", j.engagementId(), j.firmId(), j.templateId(), j.version());
    }

    static Job decode(String s) {
        String[] p = s.split("\\|", 4);
        return new Job(p[0], p[1], p[2], p[3]);
    }
}
