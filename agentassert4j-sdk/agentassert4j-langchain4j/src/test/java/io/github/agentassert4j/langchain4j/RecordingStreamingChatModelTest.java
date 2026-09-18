package io.github.agentassert4j.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.agentassert4j.model.InteractionRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * streaming-only 装饰器契约钉：仅实现流式接口的模型经 wrapStreaming 包装后
 * 回调透传与聚合录制齐备。
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
class RecordingStreamingChatModelTest {

    /**
     * 仅实现流式接口的桩——OpenAiStreamingChatModel 一类模型的形状代表。
     */
    static class StreamingOnlyStub implements StreamingChatModel {

        ChatResponse completeWith = ChatResponse.builder().aiMessage(AiMessage.from("done")).id("stream-id").build();

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("to");
            handler.onPartialResponse("ken");
            handler.onCompleteResponse(completeWith);
        }

        @Override
        public List<ChatModelListener> listeners() {
            return List.of();
        }
    }

    @Test
    @DisplayName("流式调用录一条：分片透传、完成响应聚合、记录字段齐备")
    void wrapStreamingRecordsOnComplete() {
        StreamingOnlyStub stub = new StreamingOnlyStub();
        CapturingRecorder recorder = new CapturingRecorder();
        RecordingStreamingChatModel wrapper = RecordingStreamingChatModel.wrapStreaming(stub, recorder);
        StringBuilder partials = new StringBuilder();
        AtomicInteger completes = new AtomicInteger();

        wrapper.chat(ChatRequest.builder().messages(UserMessage.from("查物流")).build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                partials.append(token);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completes.incrementAndGet();
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError("unexpected onError");
            }
        });

        assertEquals("token", partials.toString());
        assertEquals(1, completes.get());
        InteractionRecord record = recorder.sole();
        assertEquals("stream-id", record.getRecordId());
        assertEquals("done", record.getModelResponse());
        assertTrue(record.getTtftMs() != null && record.getTtftMs() >= 0);
    }
}
