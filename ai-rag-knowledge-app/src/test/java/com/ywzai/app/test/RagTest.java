//package com.ywzai.app.test;
//
//
//import com.alibaba.fastjson.JSON;
//import jakarta.annotation.Resource;
//import lombok.extern.slf4j.Slf4j;
//import org.junit.jupiter.api.Test;
//import org.springframework.ai.chat.ChatResponse;
//import org.springframework.ai.chat.messages.Message;
//import org.springframework.ai.chat.messages.UserMessage;
//import org.springframework.ai.chat.prompt.Prompt;
//import org.springframework.ai.chat.prompt.SystemPromptTemplate;
//import org.springframework.ai.document.Document;
//import org.springframework.ai.ollama.OllamaChatClient;
//import org.springframework.ai.ollama.api.OllamaOptions;
//import org.springframework.ai.reader.tika.TikaDocumentReader;
//import org.springframework.ai.transformer.splitter.TokenTextSplitter;
//import org.springframework.ai.vectorstore.PgVectorStore;
//import org.springframework.ai.vectorstore.SearchRequest;
//import org.springframework.boot.test.context.SpringBootTest;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.stream.Collectors;
//
///**
// * @Author: ywz
// * @CreateTime: 2025-09-12
// * @Description: Rag功能的测试类
// * @Version: 1.0
// */
//@Slf4j
//@SpringBootTest
//public class RagTest {
//
//    @Resource
//    private PgVectorStore pgVectorStore;
//    @Resource
//    private OllamaChatClient ollamaChatClient;
//    @Resource
//    private TokenTextSplitter tokenTextSplitter;
//
//    @Test
//    public void uploadKnowledge() {
//        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader("./data/test.text");
//        List<Document> documents = tikaDocumentReader.get();
//        List<Document> documentsSplitterList = tokenTextSplitter.apply(documents);
////        documents.forEach(document -> {
////            document.getMetadata().put("knowledge", "知识库1");
////        });
//        documentsSplitterList.forEach(document -> document.getMetadata().put("knowledge", "知识库1"));
//
//        pgVectorStore.add(documentsSplitterList);
//        log.info("上传完成");
//    }
//
//    @Test
//    public void chat() {
//        // 用户提问
//        String question = "我想知道于汶泽的年龄";
//        // 系统提示
//        String SYSTEM_PROMPT = """
//                Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
//                If unsure, simply state that you don't know.
//                Another thing you need to note is that your reply must be in Chinese!
//                DOCUMENTS:
//                    {documents}
//                """;
//        SearchRequest searchRequest = SearchRequest.query(question).withTopK(5).withFilterExpression("knowledge == '知识库1'");
//        List<Document> documents = pgVectorStore.similaritySearch(searchRequest);
//        String documentsText = documents.stream().map(Document::getContent).collect(Collectors.joining());
//        Message ragMessage = new SystemPromptTemplate(SYSTEM_PROMPT).createMessage(Map.of("documents", documentsText));
//        ArrayList<Message> messages = new ArrayList<>();
//        messages.add(ragMessage);
//        messages.add(new UserMessage(question));
//        ChatResponse chatResponse = ollamaChatClient.call(new Prompt(messages, OllamaOptions.create().withModel("deepseek-r1:1.5b")));
//        log.info("测试结果:{}", JSON.toJSONString(chatResponse));
//
//    }
//}
