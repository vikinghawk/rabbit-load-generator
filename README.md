# rabbit-load-generator

Tool to generate queue and message load that mimics CareAware's 3 main queue types:

1. quorum queues
1. classic durable mirrored queues
1. classic transient auto-delete queues

The following server-side policies are in place for each queue type:

![Queue Policies](policies.PNG)

## Configuration:

See the following classes for additional configuration:

1. [RabbitProperties](https://docs.spring.io/spring-boot/docs/current/api/org/springframework/boot/autoconfigure/amqp/RabbitProperties.html)
2. [RabbitLoadGeneratorProperties](src/main/java/com/cerner/test/RabbitLoadGeneratorProperties.java)