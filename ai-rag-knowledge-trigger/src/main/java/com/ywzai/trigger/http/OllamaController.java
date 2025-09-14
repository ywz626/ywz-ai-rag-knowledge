//package com.ywzai.trigger.http;
//
//import com.alibaba.fastjson.JSON;
//import com.ywzai.api.IAiService;
//import jakarta.annotation.Resource;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.ai.chat.messages.Message;
//import org.springframework.ai.chat.messages.UserMessage;
//import org.springframework.ai.chat.model.ChatResponse;
//import org.springframework.ai.chat.prompt.Prompt;
//import org.springframework.ai.chat.prompt.SystemPromptTemplate;
//import org.springframework.ai.document.Document;
//import org.springframework.ai.ollama.OllamaChatModel;
//import org.springframework.ai.ollama.api.OllamaOptions;
//import org.springframework.ai.vectorstore.SearchRequest;
//import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
//import org.springframework.web.bind.annotation.*;
//import reactor.core.publisher.Flux;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.Objects;
//import java.util.stream.Collectors;
//
//@Slf4j
//@RestController()
//@CrossOrigin("*")
//@RequestMapping("/api/v1/ollama/")
//public class OllamaController implements IAiService {
//
//    @Resource
//    private OllamaChatModel chatModel;
//    @Resource
//    private PgVectorStore pgVectorStore;
//
//    /**
//     * <a href="http://localhost:8090/api/v1/ollama/generate?model=deepseek-r1:1.5b&message=1+1">...</a>
//     */
//    @RequestMapping(value = "generate", method = RequestMethod.GET)
//    @Override
//    public ChatResponse generate(@RequestParam String model, @RequestParam String message) {
//        return chatClient.call(new Prompt(message, OllamaOptions.create().withModel(model)));
//    }
//
//    /**
//     * <a href="http://localhost:8090/api/v1/ollama/generate_stream?model=deepseek-r1:1.5b&message=hi">...</a>
//     */
//    @RequestMapping(value = "generate_stream", method = RequestMethod.GET)
//    @Override
//    public Flux<ChatResponse> generateStreamForMemory(@RequestParam String model,
//                                             @RequestParam String message,
//                                             @RequestParam(required = false,defaultValue = "") String ragTag,
//                                             @RequestParam String memoryId) {
//        // 系统提示
//        String SYSTEM_PROMPT = """
//                Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
//                If unsure, simply state that you don't know.
//                Another thing you need to note is that your reply must be in Chinese!
//                DOCUMENTS:
//                    {documents}
//                """;
//        SearchRequest searchRequest = SearchRequest.query(message).withTopK(5).withFilterExpression("knowledge == '知识库1'");
//        List<Document> documents = pgVectorStore.similaritySearch(searchRequest);
//        String documentsText = documents.stream().map(Document::getContent).collect(Collectors.joining());
//        Message ragMessage = new SystemPromptTemplate(SYSTEM_PROMPT).createMessage(Map.of("documents", documentsText));
//        ArrayList<Message> messages = new ArrayList<>();
//        messages.add(ragMessage);
//        messages.add(new UserMessage(message));
//        Flux<ChatResponse> chatResponseFlux = chatClient.stream(new Prompt(messages, OllamaOptions.create().withModel("deepseek-r1:1.5b")));
//        log.info("结果:{}", JSON.toJSONString(chatResponseFlux));
//        return chatResponseFlux;
//    }
//
//}
