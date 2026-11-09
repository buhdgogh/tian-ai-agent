package com.tianaiagent.infrastructure.adapter.repository.conversation;

import com.tianaiagent.domain.conversation.service.ConversationScope;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.tianaiagent.domain.conversation.model.valobj.ConversationSummary;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于文件持久化的对话记忆
 */
public class FileBasedChatMemory implements ChatMemory {
    private final String BASE_DIR;
    private final int maxMessages;
    private static final ThreadLocal<Kryo> KRYO = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
        // 保持旧类型的注册顺序，确保既有 Kryo 文件仍可读取。
        kryo.register(ChatMessageDTO.class);
        kryo.register(ArrayList.class);
        kryo.register(StructuredChatMessageDTO.class);
        kryo.register(StructuredChatMessageDTO.ToolCallDTO.class);
        kryo.register(StructuredChatMessageDTO.ToolResponseDTO.class);
        return kryo;
    });
    private final ConcurrentHashMap<String, Object> conversationLocks = new ConcurrentHashMap<>();

    // 构造对象时，指定文件保存目录
    public FileBasedChatMemory(String dir) {
        this(dir, 200);
    }

    public FileBasedChatMemory(String dir, int maxMessages) {
        this.BASE_DIR = dir;
        this.maxMessages = Math.max(1, maxMessages);
        File baseDir = new File(dir);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        validateConversationId(conversationId);
        synchronized (lockFor(conversationId)) {
            List<Message> conversationMessages = getOrCreateConversationUnlocked(conversationId);
            conversationMessages.addAll(messages);
            if (conversationMessages.size() > maxMessages) {
                conversationMessages = new ArrayList<>(conversationMessages.subList(
                        conversationMessages.size() - maxMessages, conversationMessages.size()));
            }
            saveConversationUnlocked(conversationId, conversationMessages);
        }
    }

    @Override
    public List<Message> get(String conversationId)
    {
        validateConversationId(conversationId);
        synchronized (lockFor(conversationId)) {
            return new ArrayList<>(getOrCreateConversationUnlocked(conversationId));
        }
    }

    @Override
    public void clear(String conversationId) {
        validateConversationId(conversationId);
        synchronized (lockFor(conversationId)) {
            File file = getConversationFile(conversationId);
            if (file.exists() && !file.delete()) {
                throw new IllegalStateException("删除会话失败: " + conversationId);
            }
        }
    }

    void touch(String conversationId) {
        validateConversationId(conversationId);
        synchronized (lockFor(conversationId)) {
            Path path = getConversationFile(conversationId).toPath();
            if (!Files.exists(path)) return;
            try {
                Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
            } catch (IOException e) {
                throw new IllegalStateException("更新会话活动时间失败", e);
            }
        }
    }

    /**
     * 列出所有对话摘要
     * @return
     */
    public List<ConversationSummary> listConversations() {
        List<ConversationSummary> summaries = new ArrayList<>();
        File baseDir = new File(BASE_DIR);
        if (baseDir.exists() && baseDir.isDirectory()) {
            File[] files = baseDir.listFiles((dir, name) -> name.endsWith(".kryo"));
            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName();
                    String id = fileName.substring(0, fileName.lastIndexOf("."));
                    List<Message> messages = get(id);
                    String title = "新对话";
                    // 尝试获取第一条用户消息作为标题
                    if (messages != null && !messages.isEmpty()) {
                        for (Message m : messages) {
                            if ("USER".equalsIgnoreCase(m.getMessageType().name())) {
                                String content = m.getText();
                                if (content != null && !content.trim().isEmpty()) {
                                    title = content.length() > 20 ? content.substring(0, 20) + "..." : content;
                                    break;
                                }
                            }
                        }
                    }
                    summaries.add(new ConversationSummary(id, title, Instant.ofEpochMilli(file.lastModified())));
                }
            }
        }
        return summaries;
    }

    /**
     * 列出所有对话 ID
     * @return
     */
    public List<String> listConversationIds() {
        List<String> conversationIds = new ArrayList<>();
        File baseDir = new File(BASE_DIR);
        if (baseDir.exists() && baseDir.isDirectory()) {
            File[] files = baseDir.listFiles((dir, name) -> name.endsWith(".kryo"));
            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName();
                    conversationIds.add(fileName.substring(0, fileName.lastIndexOf(".")));
                }
            }
        }
        return conversationIds;
    }

    private List<Message> getOrCreateConversationUnlocked(String conversationId) {
        File file = getConversationFile(conversationId);
        List<?> rawList = new ArrayList<>();
        if (file.exists()) {
            try (Input input = new Input(new FileInputStream(file))) {
                rawList = KRYO.get().readObject(input, ArrayList.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        List<Message> messages = new ArrayList<>();
        if (rawList != null) {
            for (Object item : rawList) {
                if (item instanceof StructuredChatMessageDTO dto) {
                    messages.add(ChatMessageMapper.fromDto(dto));
                } else if (item instanceof ChatMessageDTO dto) {
                    messages.add(ChatMessageMapper.fromLegacyDto(dto));
                } else if (item instanceof Message msg) {
                    // 如果已经是 Message 类型（旧版本数据），直接添加
                    messages.add(msg);
                }
            }
        }
        return messages;
    }

    private void saveConversationUnlocked(String conversationId, List<Message> messages) {
        File file = getConversationFile(conversationId);
        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        // 转换为 DTO 进行保存
        ArrayList<StructuredChatMessageDTO> dtos = messages.stream()
                .map(ChatMessageMapper::toDto)
                .collect(Collectors.toCollection(ArrayList::new));
        
        try {
            try (Output output = new Output(new FileOutputStream(tempFile))) {
                KRYO.get().writeObject(output, dtos);
                output.flush();
            }
            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicMoveUnsupported) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存会话失败: " + conversationId, e);
        }
    }

    private File getConversationFile(String conversationId)
    {
        return new File(BASE_DIR, conversationId + ".kryo");
    }

    private Object lockFor(String conversationId) {
        return conversationLocks.computeIfAbsent(conversationId, ignored -> new Object());
    }

    private void validateConversationId(String conversationId) {
        if (!ConversationScope.validStorageId(conversationId)) {
            throw new IllegalArgumentException("非法会话 ID");
        }
        Path base = Path.of(BASE_DIR).toAbsolutePath().normalize();
        Path target = base.resolve(conversationId + ".kryo").normalize();
        if (!target.startsWith(base)) throw new IllegalArgumentException("非法会话路径");
    }

}
