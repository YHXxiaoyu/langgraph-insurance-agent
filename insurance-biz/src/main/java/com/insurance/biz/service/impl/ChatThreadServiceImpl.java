package com.insurance.biz.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.insurance.biz.common.exception.NotFoundException;
import com.insurance.biz.domain.dto.CreateChatThreadDTO;
import com.insurance.biz.domain.dto.RenameChatThreadDTO;
import com.insurance.biz.domain.po.ChatThread;
import com.insurance.biz.domain.vo.ChatThreadVO;
import com.insurance.biz.mapper.ChatThreadMapper;
import com.insurance.biz.service.IChatThreadService;
import com.insurance.biz.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ChatThreadServiceImpl extends ServiceImpl<ChatThreadMapper, ChatThread>
        implements IChatThreadService {

    private static final String DEFAULT_TITLE = "新会话";

    @Override
    public ChatThreadVO create(CreateChatThreadDTO dto) {
        ChatThread thread = new ChatThread();
        thread.setId(UUID.randomUUID().toString());
        thread.setUserId(UserContext.getUserId());
        thread.setTitle(dto != null && StringUtils.hasText(dto.title())
                ? dto.title() : DEFAULT_TITLE);
        save(thread);

        // created_at / updated_at 是数据库默认值，插完得回查一次才拿得到
        return ChatThreadVO.from(getById(thread.getId()));
    }

    @Override
    public List<ChatThreadVO> listMine() {
        return lambdaQuery()
                .eq(ChatThread::getUserId, UserContext.getUserId())
                .orderByDesc(ChatThread::getUpdatedAt)
                .list()
                .stream()
                .map(ChatThreadVO::from)
                .toList();
    }

    @Override
    public void rename(String id, RenameChatThreadDTO dto) {
        requireOwned(id);

        // 只带 id + title 的新对象：如果复用查出来的实体，
        // updated_at 会被原样写回去，数据库的 ON UPDATE CURRENT_TIMESTAMP 就不生效了
        ChatThread update = new ChatThread();
        update.setId(id);
        update.setTitle(dto.title());
        updateById(update);
    }

    @Override
    public void delete(String id) {
        requireOwned(id);
        removeById(id);
    }

    /**
     * 取会话并校验归属。
     * <p>
     * 查询条件同时带 id 和 userId，所以别人的会话在这里就是「查不到」。
     * 不能先按 id 查出来再比较 userId —— 那等于把「这个 id 存在」这件事泄露出去。
     */
    private ChatThread requireOwned(String id) {
        ChatThread thread = lambdaQuery()
                .eq(ChatThread::getId, id)
                .eq(ChatThread::getUserId, UserContext.getUserId())
                .one();
        if (thread == null) {
            // 「不存在」和「不是你的」返回同一种结果，避免被枚举探测
            throw new NotFoundException("会话不存在");
        }
        return thread;
    }
}
