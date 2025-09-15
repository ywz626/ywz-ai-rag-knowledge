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
