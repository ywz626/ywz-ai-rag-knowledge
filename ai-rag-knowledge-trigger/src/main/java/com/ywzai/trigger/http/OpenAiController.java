package com.ywzai.trigger.http;

import com.ywzai.api.IAiService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/openai/")
public class OpenAiController implements IAiService {

    @Resource
    private OpenAiChatClient chatClient;
    @Resource
    private PgVectorStore pgVectorStore;

    @RequestMapping(value = "generate", method = RequestMethod.GET)
    @Override
    public ChatResponse generate(@RequestParam String model, @RequestParam String message) {
        return chatClient.call(new Prompt(
                message,
                OpenAiChatOptions.builder()
                        .withModel(model)
                        .build()
        ));
    }



    @RequestMapping(value = "generate_stream", method = RequestMethod.GET)
    @Override
    public Flux<ChatResponse> generateStreamForMemory(@RequestParam String model,
                                                      @RequestParam String message,
                                                      @RequestParam(required = false, defaultValue = "") String ragTag,
                                                      @RequestParam String memoryId) {
        if (Objects.equals(ragTag, "")) {
            return chatClient.stream(new Prompt(message, OllamaOptions.create().withModel(model)));
        }

        String SYSTEM_PROMPT_RAG = """
                Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
                If unsure, simply state that you don't know.
                Another thing you need to note is that your reply must be in Chinese!
                DOCUMENTS:
                    {documentsForRag}
                """;

        String SYSTEM_PROMPT_HISTORY = """
                你是一个有长期记忆的 AI 助手。
                使用历史对话信息来理解上下文并给出准确回答，但请假装你天然记得这些历史对话。
                如果不确定答案，可以直接说不知道。
                回复必须使用中文。
                历史对话记录:
                    {historyForContext}
                现在用户输入新的消息，请根据历史对话和当前问题生成回答。
                """;
        // 指定文档搜索
        SearchRequest request = SearchRequest.query(message)
                .withTopK(5)
                .withFilterExpression("knowledge == '" + ragTag + "'");

        List<Document> documentsForRag = pgVectorStore.similaritySearch(request);
        String documentCollectors = documentsForRag.stream().map(Document::getContent).collect(Collectors.joining());
        Message ragMessage = new SystemPromptTemplate(SYSTEM_PROMPT_RAG).createMessage(Map.of("documentsForRag", documentCollectors));
        request = SearchRequest.query(message)
                .withTopK(5)
                .withFilterExpression("historychat == '" + memoryId + "'");
        List<Document> documentsForHistory = pgVectorStore.similaritySearch(request);
        String documentCollectorsForHistory = documentsForHistory.stream().map(Document::getContent).collect(Collectors.joining());
        Message ragMessageForHistory = new SystemPromptTemplate(SYSTEM_PROMPT_HISTORY).createMessage(Map.of("historyForContext", documentCollectorsForHistory));
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(message));
        messages.add(ragMessage);
        messages.add(ragMessageForHistory);
        saveUserInput(memoryId, message);
        return chatClient.stream(new Prompt(
                messages,
                OpenAiChatOptions.builder()
                        .withModel(model)
                        .build()
        ));
    }
    public void saveUserInput(String memoryId, String message) {
        String documentId = UUID.randomUUID().toString();
        Document doc = new Document(documentId,message,Map.of("historychat",memoryId));

        // 2. 存入 PgVectorStore（内部会调用 EmbeddingClient 生成向量）
        pgVectorStore.add(List.of(doc));
    }

}