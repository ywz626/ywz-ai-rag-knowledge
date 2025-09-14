package com.ywzai.api;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Flux;

public interface IAiService {

    ChatResponse generate(String model, String message);


    Flux<ChatResponse> generateStreamForMemory(@RequestParam String model,
                                               @RequestParam String message,
                                               @RequestParam(required = false, defaultValue = "") String ragTag,
                                               @RequestParam String memoryId);
}
