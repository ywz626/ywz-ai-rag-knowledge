package com.ywzai.trigger.http;


import com.ywzai.api.IRagService;
import com.ywzai.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.core.io.PathResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.HashMap;

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
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private RedissonClient redissonClient;

    @Override
    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    public Response<List<String>> queryRagTagList() {
        RList<String> ragTagList = redissonClient.getList("ragTag");
        log.info("使用查询知识库列表功能");
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
        
        log.info("开始流式上传文件到向量数据库，文件数量: {}, 标签: {}", files.size(), ragTag);
        
        try {
            // 使用线程池处理文件上传，限制并发数避免资源耗尽
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(files.size(), 3));
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger totalSegments = new AtomicInteger(0);
            
            List<CompletableFuture<Void>> futures = files.stream()
                .map(file -> CompletableFuture.runAsync(() -> {
                    try {
                        int segments = processFileWithSpringAI(file, ragTag);
                        totalSegments.addAndGet(segments);
                        int processed = processedCount.incrementAndGet();
                        log.info("文件处理进度: {}/{}, 当前文件: {}, 生成片段数: {}", 
                               processed, files.size(), file.getOriginalFilename(), segments);
                    } catch (Exception e) {
                        log.error("处理文件失败: {}", file.getOriginalFilename(), e);
                        throw new RuntimeException("文件处理失败: " + file.getOriginalFilename(), e);
                    }
                }, executor))
                .toList();
            
            // 等待所有文件处理完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            executor.shutdown();
            
            // 更新Redis中的标签列表
            RList<String> ragTagList = redissonClient.getList("ragTag");
            if (!ragTagList.contains(ragTag)) {
                ragTagList.add(ragTag);
            }
            
            log.info("所有文件流式上传完成！总计处理 {} 个文件，生成 {} 个文本片段", files.size(), totalSegments.get());
            return Response.<String>builder()
                    .code("0000")
                    .info(String.format("上传成功，处理了 %d 个文件，生成 %d 个文本片段", files.size(), totalSegments.get()))
                    .build();
                    
        } catch (Exception e) {
            log.error("文件上传过程中发生错误", e);
            return Response.<String>builder()
                    .code("500")
                    .info("文件上传失败: " + e.getMessage())
                    .build();
        }
    }
    
    /**
     * 使用Spring AI流式处理单个文件
     * 
     * @param file 上传的文件
     * @param ragTag 知识库标签
     * @return 处理的文本片段数量
     * @throws Exception 文件处理异常
     */
    private int processFileWithSpringAI(MultipartFile file, String ragTag) throws Exception {
        log.info("开始流式处理文件: {}, 大小: {} MB, 文件类型: {}", 
                file.getOriginalFilename(), 
                file.getSize() / (1024.0 * 1024.0),
                file.getContentType());
        
        try {
            // 使用Spring AI的TikaDocumentReader解析文档
            TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(file.getResource());
            List<Document> documents = tikaDocumentReader.get();
            
            if (documents.isEmpty()) {
                log.warn("文件 {} 解析后无内容，可能是不支持的格式或空文件", file.getOriginalFilename());
                return 0;
            }
            
            // 成功解析文件后记录日志
            String fileExtension = file.getOriginalFilename().toLowerCase().substring(file.getOriginalFilename().lastIndexOf('.') + 1);
            log.info("成功解析 {} 格式文件: {}", fileExtension.toUpperCase(), file.getOriginalFilename());
            
            // 获取文档总字符数，用于估算处理批次
            int totalCharacters = documents.stream()
                .mapToInt(doc -> doc.getText().length())
                .sum();
            
            log.info("文件 {} 包含 {} 个文档，总字符数: {}", file.getOriginalFilename(), documents.size(), totalCharacters);
            
            // 调试：检查文档内容
            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                String content = doc.getText();
                log.info("文档 {} 内容长度: {}，前100个字符: [{}]", 
                        i, content.length(), 
                        content.length() > 100 ? content.substring(0, 100) : content);
                log.info("文档 {} 元数据: {}", i, doc.getMetadata());
            }
            
            // 如果所有文档内容都为空，尝试直接读取文件内容
            if (totalCharacters == 0) {
                log.warn("Tika解析结果为空，尝试直接读取文件内容...");
                try {
                    String rawContent = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    log.info("直接读取文件长度: {}，前200个字符: [{}]", 
                            rawContent.length(), 
                            rawContent.length() > 200 ? rawContent.substring(0, 200) : rawContent);
                    
                    // 尝试其他编码
                    if (rawContent.trim().isEmpty() || rawContent.contains("�")) {
                        log.info("UTF-8编码可能有问题，尝试GBK编码...");
                        String gbkContent = new String(file.getBytes(), java.nio.charset.Charset.forName("GBK"));
                        log.info("GBK编码读取文件长度: {}，前200个字符: [{}]", 
                                gbkContent.length(), 
                                gbkContent.length() > 200 ? gbkContent.substring(0, 200) : gbkContent);
                        
                        // 如果GBK编码读取成功，使用这个内容
                        if (!gbkContent.trim().isEmpty() && !gbkContent.contains("�")) {
                            log.info("使用GBK编码成功读取文件内容");
                            Document manualDoc = new Document(gbkContent);
                            manualDoc.getMetadata().put("source", file.getOriginalFilename());
                            manualDoc.getMetadata().put("encoding", "GBK");
                            documents = List.of(manualDoc);
                            totalCharacters = gbkContent.length();
                        }
                    } else if (!rawContent.trim().isEmpty()) {
                        log.info("使用UTF-8编码成功读取文件内容");
                        Document manualDoc = new Document(rawContent);
                        manualDoc.getMetadata().put("source", file.getOriginalFilename());
                        manualDoc.getMetadata().put("encoding", "UTF-8");
                        documents = List.of(manualDoc);
                        totalCharacters = rawContent.length();
                    }
                } catch (Exception e) {
                    log.error("直接读取文件内容失败: {}", file.getOriginalFilename(), e);
                }
            }
            
            // 再次检查是否有内容
            if (totalCharacters == 0) {
                log.warn("文件 {} 经过多种方式解析后仍然无内容，跳过处理", file.getOriginalFilename());
                return 0;
            }
            
            log.info("最终处理：文件 {} 包含 {} 个文档，总字符数: {}", file.getOriginalFilename(), documents.size(), totalCharacters);
            
            // 流式分割处理大文档
            List<Document> allSplitDocuments = new ArrayList<>(); 
            int processedChars = 0;
            
            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                
                // 如果单个文档太大，需要分批处理
                if (doc.getText().length() > 500000) { // 超过50万字符的文档需要分批
                    List<Document> batchSplitDocs = proceseLargeDocumentInBatches(doc, ragTag, file.getOriginalFilename());
                    allSplitDocuments.addAll(batchSplitDocs);
                } else {
                    // 普通大小的文档直接分割
                    List<Document> splitDocs = tokenTextSplitter.apply(List.of(doc));
                    allSplitDocuments.addAll(splitDocs);
                }
                
                processedChars += doc.getText().length();
                
                // 每处理一定数量的字符就输出进度
                if (i % 10 == 0 || i == documents.size() - 1) {
                    double progress = (double) processedChars / totalCharacters * 100;
                    log.debug("文件 {} 文档分割进度: {:.1f}%, 已生成片段: {}", 
                             file.getOriginalFilename(), progress, allSplitDocuments.size());
                }
            }
            
            // 批量添加元数据并存储到向量数据库
            int batchSize = 10; // 每批10个文档片段
            int totalBatches = (allSplitDocuments.size() + batchSize - 1) / batchSize;
            
            for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
                int start = batchIndex * batchSize;
                int end = Math.min(start + batchSize, allSplitDocuments.size());
                
                List<Document> batchDocuments = allSplitDocuments.subList(start, end);
                
                // 为每个文档片段添加元数据
                final int currentBatchIndex = batchIndex; // 创建最终变量供 Lambda 使用
                batchDocuments.forEach(document -> {
                    document.getMetadata().put("knowledge", ragTag);
                    document.getMetadata().put("filename", file.getOriginalFilename());
                    document.getMetadata().put("fileSize", String.valueOf(file.getSize()));
                    document.getMetadata().put("uploadTime", String.valueOf(System.currentTimeMillis()));
                    document.getMetadata().put("batchIndex", String.valueOf(currentBatchIndex));
                });
                
                // 存储到Spring AI PgVector
                try {
                    pgVectorStore.add(batchDocuments);
                    log.debug("成功存储批次 {}/{}, 文档片段数: {}", batchIndex + 1, totalBatches, batchDocuments.size());
                } catch (Exception e) {
                    log.error("存储批次 {}/{} 失败", batchIndex + 1, totalBatches, e);
                    throw new RuntimeException("向量存储失败", e);
                }
                
                // 适当延迟，避免对数据库造成过大压力
                if (batchIndex < totalBatches - 1) {
                    Thread.sleep(50); // 50ms延迟
                }
            }
            
            log.info("文件 {} 流式处理完成，总计 {} 个文本片段", file.getOriginalFilename(), allSplitDocuments.size());
            return allSplitDocuments.size();
            
        } catch (Exception e) {
            log.error("流式处理文件失败: {}", file.getOriginalFilename(), e);
            throw new Exception("文件处理失败: " + file.getOriginalFilename(), e);
        }
    }
    
    /**
     * 分批处理超大文档
     * 
     * @param largeDoc 大文档
     * @param ragTag 知识库标签
     * @param filename 文件名
     * @return 分割后的文档列表
     */
    private List<Document> proceseLargeDocumentInBatches(Document largeDoc, String ragTag, String filename) {
        List<Document> allSplitDocs = new ArrayList<>();
        String content = largeDoc.getText();
        int chunkSize = 400000; // 每次处理40万字符
        
        log.info("处理超大文档: {}, 总长度: {} 字符，分为 {} 个批次", 
                filename, content.length(), (content.length() + chunkSize - 1) / chunkSize);
        
        for (int start = 0; start < content.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, content.length());
            String chunkContent = content.substring(start, end);
            
            // 创建临时文档进行分割
            Document chunkDoc = new Document(chunkContent, new HashMap<>(largeDoc.getMetadata()));
            chunkDoc.getMetadata().put("chunkStart", String.valueOf(start));
            chunkDoc.getMetadata().put("chunkEnd", String.valueOf(end));
            
            // 对chunk进行分割
            List<Document> splitChunk = tokenTextSplitter.apply(List.of(chunkDoc));
            allSplitDocs.addAll(splitChunk);
            
            log.debug("已处理文档块: {}-{}, 生成片段: {}", start, end, splitChunk.size());
        }
        
        return allSplitDocs;
    }

    @Override
    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    public Response<String> analyzeGitRepository(String repoUrl) throws IOException {
        String localPath = "./git-cloned-repo";
        String repoProjectName = extractProjectName(repoUrl);
        log.info("克隆路径：{}", new File(localPath).getAbsolutePath());

        FileUtils.deleteDirectory(new File(localPath));

        Git git = null;
        int retryCount = 0;
        final int maxRetries = 3;
        boolean clonedSuccessfully = false;

        while (retryCount < maxRetries && !clonedSuccessfully) {
            try {
                git = Git.cloneRepository()
                        .setURI(repoUrl)
                        .setDirectory(new File(localPath))
                        .setCloneAllBranches(false)
                        .setTimeout(600) // 10分钟超时
                        .call();
                clonedSuccessfully = true;
            } catch (GitAPIException e) {
                retryCount++;

                // 记录具体异常信息
                Throwable cause = e.getCause();
                if (cause instanceof java.net.SocketException &&
                        cause.getMessage().contains("Connection reset")) {
                    log.warn("连接被重置，可能是网络不稳定或服务器问题");
                }

                if (retryCount >= maxRetries) {
                    log.error("克隆仓库失败，已重试 {} 次: {}", maxRetries, repoUrl, e);

                    // 清理可能创建的不完整目录
                    try {
                        File localDir = new File(localPath);
                        if (localDir.exists()) {
                            FileUtils.deleteDirectory(localDir);
                        }
                    } catch (IOException cleanupException) {
                        log.warn("清理不完整克隆目录失败: {}", localPath, cleanupException);
                    }

                    throw new RuntimeException("克隆仓库失败: " + repoUrl +
                            "，可能是网络连接问题，请稍后重试", e);
                }

                log.warn("克隆仓库失败，{} 秒后进行第 {} 次重试: {}",
                        5 * retryCount, retryCount, repoUrl, e);

                try {
                    Thread.sleep(5000 * retryCount); // 递增等待时间
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("线程被中断", ie);
                }
            }
        }

        Files.walkFileTree(Paths.get(localPath), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                log.info("{} 遍历解析路径，上传知识库:{}", repoProjectName, file.getFileName());
                try {
                    TikaDocumentReader reader = new TikaDocumentReader(new PathResource(file));
                    List<Document> documents = reader.get();
                    List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

                    documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));

                    pgVectorStore.accept(documentSplitterList);
                } catch (Exception e) {
                    log.error("遍历解析路径，上传知识库失败:{}", file.getFileName());
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                log.info("Failed to access file: {} - {}", file.toString(), exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        git.close();
        FileUtils.deleteDirectory(new File(localPath));

        RList<String> elements = redissonClient.getList("ragTag");
        if (!elements.contains(repoProjectName)) {
            elements.add(repoProjectName);
        }

        git.close();

        log.info("遍历解析路径，上传完成:{}", repoUrl);

        return Response.<String>builder().code("0000").info("调用成功").build();
    }
    /**
     * 提取仓库名称
     *
     * @param repoUrl 仓库URL
     * @return 仓库名称
     */
    private String extractProjectName(String repoUrl) {
        String[] parts = repoUrl.split("/");
        String projectNameWithGit = parts[parts.length - 1];
        return projectNameWithGit.replace(".git", "");
    }
}
