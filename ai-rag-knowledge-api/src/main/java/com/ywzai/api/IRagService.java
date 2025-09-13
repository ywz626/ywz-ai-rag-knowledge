package com.ywzai.api;


import com.ywzai.api.response.Response;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * @Author: ywz
 * @CreateTime: 2025-09-12
 * @Description: Rag知识库服务相关接口
 * @Version: 1.0
 */
public interface IRagService {

    Response<List<String>> queryRagTagList();

    Response<String> uploadFile(String ragTag, List<MultipartFile> files);

    Response<String> analyzeGitRepository(String repoUrl) throws IOException;

}
