package com.ywzai.trigger.http;


import com.ywzai.api.IRagService;
import com.ywzai.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * @Author: ywz
 * @CreateTime: 2025-09-12
 * @Description: Rag知识库接口
 * @Version: 1.0
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RagController implements IRagService {

    @Resource
    private PgVectorStore pgVectorStore;
    @Resource
    private OllamaChatClient ollamaChatClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private RedissonClient redissonClient;

    @Override
    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    public Response<List<String>> queryRagTagList() {
        RList<String> ragTagList = redissonClient.getList("ragTag");
        return Response.<List<String>>builder().code("0000").info("调用成功").data(ragTagList).build();
    }

    @Override
    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    public Response<String> uploadFile(@RequestParam String ragTag,@RequestParam("files") List<MultipartFile> files) {
        // 添加空值检查
        if (files == null || files.isEmpty()) {
            return Response.<String>builder()
                    .code("400")
                    .info("文件列表不能为空")
                    .build();
        }
        log.info("上传数据库开始！！！");
        for (MultipartFile file : files) {
            TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(file.getResource());
            List<Document> documents = tikaDocumentReader.get();
            List<Document> documentsSplitter = tokenTextSplitter.apply(documents);
            documentsSplitter.forEach(document -> document.getMetadata().put("knowledge", ragTag));
            pgVectorStore.add(documentsSplitter);
            RList<String> ragTagList = redissonClient.getList("ragTag");
            if (!ragTagList.contains(ragTag)) {
                ragTagList.add(ragTag);
            }
        }
        log.info("上传数据库结束！！！");
        return Response.<String>builder().code("0000").info("调用成功").build();
    }
}
