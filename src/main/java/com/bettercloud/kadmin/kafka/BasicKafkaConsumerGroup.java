package com.bettercloud.kadmin.kafka;

import com.bettercloud.kadmin.api.kafka.KadminConsumerConfig;
import com.bettercloud.kadmin.api.kafka.KadminConsumerGroup;
import com.bettercloud.kadmin.api.kafka.KadminProducerConfig;
import com.bettercloud.kadmin.api.kafka.MessageHandler;
import com.bettercloud.kadmin.api.kafka.avro.AvroConsumerGroup;
import com.google.common.collect.Lists;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by davidesposito on 7/20/16.
 */
public class BasicKafkaConsumerGroup<ValueT> implements KadminConsumerGroup<String, ValueT> {

    private KafkaConsumer<String, ValueT> consumer;
    private final KadminConsumerConfig config;
    private final String clientId;
    private final String groupId;
    private final AtomicLong lastOffset;

    private final List<MessageHandler<String, ValueT>> handlers;

    public BasicKafkaConsumerGroup(KadminConsumerConfig config) {
        assert(config != null);
        assert(config.getKeyDeserializer() != null);
        assert(config.getValueDeserializer() != null);

        this.config = config;
        this.clientId = UUID.randomUUID().toString();
        this.groupId = UUID.randomUUID().toString();
        this.lastOffset = new AtomicLong(-1);
        this.handlers = Lists.newArrayList();
    }

    /**
     * Gets called before init(). Set any default configs here
     * @param config
     */
    protected void initConfig(KadminConsumerConfig config) { }

    protected void init() {
        initConfig(config);

        final Properties properties = new Properties();
        properties.put("bootstrap.servers", config.getKafkaHost());
        properties.put("schema.registry.url", config.getSchemaRegistryUrl());

        properties.put("client.id", getClientId());
        properties.put("group.id", getGroupId());

        properties.put("enable.auto.commit", false);
        properties.put("fetch.max.wait.ms", 1000);
        properties.put("max.partition.fetch.bytes", 32 * 1024);
        properties.put("auto.offset.reset", "earliest");

        properties.put("key.deserializer", config.getKeyDeserializer());
        properties.put("value.deserializer", config.getValueDeserializer());

        this.consumer = new KafkaConsumer<>(properties);
    }

    @Override
    public KadminConsumerConfig getConfig() {
        return config;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public String getGroupId() {
        return groupId;
    }

    @Override
    public long getOffset() {
        return lastOffset.get();
    }

    @Override
    public void setOffset(long newOffset) {
        if (consumer != null) {
            // TODO: for real
            // consumer.seek(partition, newOffset);
            lastOffset.set(newOffset);
        }
    }

    @Override
    public void shutdown() {
        if (consumer != null) {
            consumer.wakeup();
            consumer = null;
        }
    }

    @Override
    public void run() {
        if (consumer != null) {
            return;
        } else {
            init();
        }
        try {
            consumer.subscribe(Arrays.asList(config.getTopic()));

            while (true) {
                ConsumerRecords<String, ValueT> records = consumer.poll(0);
                for (ConsumerRecord<String, ValueT> record : records) {
                    lastOffset.set(record.offset());
                    synchronized (handlers) {
                        handlers.stream().forEach(h -> h.handle(record));
                    }
                }
                try {
                    Thread.sleep(500); // TODO: configure
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } catch (WakeupException e) {
            // ignore for shutdown
        } finally {
            consumer.close();
        }
    }

    @Override
    public void register(MessageHandler<String, ValueT> handler) {
        if (handler != null) {
            synchronized (handlers) {
                handlers.add(handler);
            }
        }
    }

    @Override
    public boolean remove(MessageHandler<String, ValueT> handler) {
        if (handler != null) {
            synchronized (handlers) {
                if (handlers.contains(handler)) {
                    handlers.remove(handler);
                    return true;
                }
            }
        }
        return false;
    }
}
