package org.tanzu.zantu.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.tanzu.zantu.message.RabbitMQConfiguration;
import org.tanzu.zantu.message.ZantuChatMessage;

import java.util.UUID;

@Service
public class ZantuService {

    private static final Logger logger = LoggerFactory.getLogger(ZantuService.class);

    private final ChatClient chatClient;
    private final RabbitTemplate rabbitTemplate;

    @Value("classpath:/prompts/system-prompt.st")
    private Resource systemPrompt;

    public ZantuService(ChatClient.Builder chatClientBuilder, RabbitTemplate rabbitTemplate) {
        this.chatClient = chatClientBuilder.build();
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Listen for incoming messages from cf-mcp-client
     */
    @RabbitListener(queues = RabbitMQConfiguration.ZANTU_REQUEST_QUEUE)
    public void processMessage(ZantuChatMessage incomingMessage) {
        logger.info("Zantu received message: messageId={}, conversationId={}",
                incomingMessage.messageId(), incomingMessage.conversationId());

        try {
            // Process the message with the LLM
            String response = generateResponse(incomingMessage.content());

            // Create response message
            String responseMessageId = UUID.randomUUID().toString();
            ZantuChatMessage responseMessage = ZantuChatMessage.fromZantu(
                    responseMessageId,
                    incomingMessage.conversationId(),
                    response
            );

            // Send response back to cf-mcp-client
            sendResponse(responseMessage);

            logger.info("Zantu sent response: messageId={}, conversationId={}",
                    responseMessageId, incomingMessage.conversationId());

        } catch (Exception e) {
            logger.error("Error processing message from cf-mcp-client", e);

            // Send error response
            String errorResponse = "I apologize, but I encountered an error processing your request. Please try again.";
            ZantuChatMessage errorMessage = ZantuChatMessage.fromZantu(
                    UUID.randomUUID().toString(),
                    incomingMessage.conversationId(),
                    errorResponse
            );
            sendResponse(errorMessage);
        }
    }

    /**
     * Generate a response using the LLM
     */
    private String generateResponse(String userMessage) {
        logger.debug("Generating response for message: {}", userMessage);

        return chatClient
                .prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
    }

    /**
     * Send response back to cf-mcp-client
     */
    private void sendResponse(ZantuChatMessage responseMessage) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfiguration.ZANTU_EXCHANGE,
                RabbitMQConfiguration.ZANTU_RESPONSE_ROUTING_KEY,
                responseMessage
        );
    }
}