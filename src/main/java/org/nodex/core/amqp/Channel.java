package org.nodex.core.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.nodex.core.NoArgCallback;
import org.nodex.core.Nodex;
import org.nodex.core.composition.Completion;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User: timfox
 * Date: 02/07/2011
 * Time: 07:25
 */
public class Channel {
  private com.rabbitmq.client.Channel channel;

  Channel(com.rabbitmq.client.Channel channel) {
    this.channel = channel;
  }

  public void publish(final String exchange, final String routingKey, final AMQP.BasicProperties props, final byte[] body) {
    try {
      channel.basicPublish(exchange, routingKey, props, body);
    } catch (IOException e) {
      //TODO handle exception by passing them back on callback
      e.printStackTrace();
    }
  }

  public void publish(final String exchange, final String routingKey, final AMQP.BasicProperties props, final String message) {
    try {
      publish(exchange, routingKey, null, message.getBytes("UTF-8"));
    } catch (UnsupportedEncodingException willNeverHappen) {
    }
  }

  public void declareQueue(final String queueName, final boolean durable, final boolean exclusive, final boolean autoDelete,
                           final NoArgCallback doneCallback) {
    Nodex.instance.executeInBackground(new Runnable() {
      public void run() {
        try {
          channel.queueDeclare(queueName, durable, exclusive, autoDelete, null);
          doneCallback.onEvent();
        } catch (IOException e) {
          //TODO handle exception by passing them back on callback
          e.printStackTrace();
        }
      }
    });
  }

  public void subscribe(final String queueName, final boolean autoAck, final MessageCallback messageCallback) {
    Nodex.instance.executeInBackground(new Runnable() {
      public void run() {
        try {
          channel.basicConsume(queueName, autoAck, "blah",
              new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag,
                                           Envelope envelope,
                                           AMQP.BasicProperties properties,
                                           byte[] body)
                    throws IOException {
                  messageCallback.onMessage(properties, body);
                }
              });
        } catch (IOException e) {
          //TODO handle exception by passing them back on callback
          e.printStackTrace();
        }
      }
    });
  }

  private Map<String, MessageCallback> callbacks = new ConcurrentHashMap<String, MessageCallback>();
  private volatile String responseQueue;
  private Completion responseQueueSetup = new Completion();

  private synchronized void createResponseQueue() {
    if (responseQueue == null) {
      final String queueName = UUID.randomUUID().toString();
      declareQueue(queueName, false, true, true, new NoArgCallback() {
        public void onEvent() {
          responseQueue = queueName;
          responseQueueSetup.complete(); //Queue is now set up
          subscribe(queueName, true, new MessageCallback() {
            public void onMessage(AMQP.BasicProperties props, byte[] body) {
              String cid = props.getCorrelationId();
              if (cid == null) {
                //TODO better error reporting
                System.err.println("No correlation id");
              } else {
                MessageCallback cb = callbacks.get(cid);
                if (cb == null) {
                  System.err.println("No callback for correlation id");
                } else {
                  cb.onMessage(props, body);
                }
              }
            }
          });
        }
      });
    }
  }

  // Request-response pattern
  public void request(final String exchange, final String routingKey, final AMQP.BasicProperties props, final byte[] body, MessageCallback responseCallback) {
    if (responseQueue == null) createResponseQueue();
    //We make sure we don't actually send until the response queue has been setup, this is done by using a
    //Completion
    responseQueueSetup.onComplete(new NoArgCallback() {
      public void onEvent() {
        String cid = UUID.randomUUID().toString();
        props.setCorrelationId(cid);
        props.setReplyTo(responseQueue);
        publish(exchange, routingKey, props, body);
      }
    });
  }


  public void close(final NoArgCallback doneCallback) {
    Nodex.instance.executeInBackground(new Runnable() {
      public void run() {
        try {
          channel.close();
          doneCallback.onEvent();
        } catch (IOException e) {
          //TODO handle exception by passing them back on callback
          e.printStackTrace();
        }
      }
    });
  }


}