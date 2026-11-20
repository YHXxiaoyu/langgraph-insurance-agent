package com.insurance.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.insurance.biz.domain.dto.CreateChatThreadDTO;
import com.insurance.biz.domain.dto.RenameChatThreadDTO;
import com.insurance.biz.domain.po.ChatThread;
import com.insurance.biz.domain.vo.ChatThreadVO;

import java.util.List;

/**
 * 会话管理。
 * <p>
 * 所有方法内部都会从 UserContext 取当前用户并参与查询条件，
 * 调用方无法传入 userId —— 从接口层面就堵死越权。
 */
public interface IChatThreadService extends IService<ChatThread> {

    ChatThreadVO create(CreateChatThreadDTO dto);

    /** 当前用户的会话列表，按最后更新时间倒序 */
    List<ChatThreadVO> listMine();

    void rename(String id, RenameChatThreadDTO dto);

    void delete(String id);
}
