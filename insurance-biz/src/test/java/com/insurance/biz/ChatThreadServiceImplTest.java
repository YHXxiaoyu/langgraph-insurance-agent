package com.insurance.biz;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import com.insurance.biz.client.AgentClient;
import com.insurance.biz.common.exception.NotFoundException;
import com.insurance.biz.domain.dto.CreateChatThreadDTO;
import com.insurance.biz.domain.dto.RenameChatThreadDTO;
import com.insurance.biz.domain.po.ChatThread;
import com.insurance.biz.domain.po.User;
import com.insurance.biz.domain.vo.ChatThreadVO;
import com.insurance.biz.enums.UserStatus;
import com.insurance.biz.mapper.ChatThreadMapper;
import com.insurance.biz.security.LoginUser;
import com.insurance.biz.service.impl.ChatThreadServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 会话服务单元测试。
 * <p>
 * baseMapper 是 ServiceImpl 的 protected 字段，塞一个 mock 进去，
 * 这样 chainQuery（lambdaQuery()）和 save/getById 都会走 mock，不需要 MySQL。
 * 重点验证两件事：归属条件在 SQL 里；删除/重命名越权时按「不存在」处理。
 */
class ChatThreadServiceImplTest {

    private static final Long ME = 7L;

    private ChatThreadMapper mapper;
    private AgentClient agentClient;
    private ChatThreadServiceImpl service;
    /**
     * 纯单元测试不经过 MyBatis 启动流程，实体元数据（表名、字段→列名映射）不会自动注册。
     * lambda 条件（ChatThread::getUserId）要渲染成 SQL 片段必须靠这份缓存，
     * 所以测试自己手动初始化一次，否则 getSqlSegment() 会抛
     * "can not find lambda cache for this entity"。
     */
    @BeforeAll
    static void initEntityMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ChatThread.class);
    }
    @BeforeEach
    void setUp() {
        mapper = mock(ChatThreadMapper.class);
        agentClient = mock(AgentClient.class);

        // MyBatis-Plus 3.5.9 的 lambdaQuery() 会从 MyBatis 的 mapper 代理上反推实体类，
        // Mockito 的假对象没有代理，所以这里直接覆写 getEntityClass() 把实体类给出来。
        // 这是整个测试能跑起来的关键，不是凑合。
        service = new ChatThreadServiceImpl(agentClient) {
            @Override
            public Class<ChatThread> getEntityClass() {
                return ChatThread.class;
            }
        };

        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        loginAs(ME);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------- create ----------------

    @Test
    @DisplayName("新建会话：不传标题用默认名，归属当前登录用户")
    void createWithDefaultTitle() {
        when(mapper.insert(any(ChatThread.class))).thenReturn(1);
        // 回查时按传进来的 id 返回，模拟数据库里刚插入的那一行
        when(mapper.selectById(any()))
                .thenAnswer(invocation -> thread(invocation.getArgument(0), "新会话"));

        ChatThreadVO vo = service.create(new CreateChatThreadDTO(null));

        ArgumentCaptor<ChatThread> captor = ArgumentCaptor.forClass(ChatThread.class);
        verify(mapper).insert(captor.capture());
        ChatThread saved = captor.getValue();

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getUserId()).isEqualTo(ME);
        assertThat(saved.getTitle()).isEqualTo("新会话");
        assertThat(vo.id()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("新建会话：传了标题就用传的")
    void createWithGivenTitle() {
        when(mapper.insert(any(ChatThread.class))).thenReturn(1);
        when(mapper.selectById(any()))
                .thenAnswer(invocation -> thread(invocation.getArgument(0), "我的保单"));

        service.create(new CreateChatThreadDTO("我的保单"));

        ArgumentCaptor<ChatThread> captor = ArgumentCaptor.forClass(ChatThread.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("我的保单");
    }

    // ---------------- list ----------------

    @Test
    @DisplayName("会话列表：SQL 里必须带 user_id 条件，且按更新时间倒序")
    void listMineFiltersByCurrentUser() {
        when(mapper.selectList(any()))
                .thenReturn(List.of(thread("t2", "第二个"), thread("t1", "第一个")));

        List<ChatThreadVO> result = service.listMine();

        assertThat(result)
                .extracting(ChatThreadVO::id)
                .containsExactly("t2", "t1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ChatThread>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("user_id")
                .contains("updated_at DESC");
    }

    // ---------------- rename ----------------

    @Test
    @DisplayName("重命名：更新对象只带 id 和 title，不能把旧的 updated_at 写回去")
    void renameUpdatesOnlyIdAndTitle() {
        when(mapper.selectOne(any())).thenReturn(thread("t1", "旧标题"));

        service.rename("t1", new RenameChatThreadDTO("新标题"));

        ArgumentCaptor<ChatThread> captor = ArgumentCaptor.forClass(ChatThread.class);
        verify(mapper).updateById(captor.capture());
        ChatThread update = captor.getValue();

        assertThat(update.getId()).isEqualTo("t1");
        assertThat(update.getTitle()).isEqualTo("新标题");
        assertThat(update.getUserId()).isNull();
        assertThat(update.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("重命名别人的会话：按「不存在」处理，且不写库")
    void renameForeignThreadIsNotFound() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.rename("t9", new RenameChatThreadDTO("x")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("会话不存在");
        // updateById 有实体和集合两个重载，any() 推不出类型，必须写 any(ChatThread.class)
        verify(mapper, never()).updateById(any(ChatThread.class));
    }

    // ---------------- delete ----------------

    @Test
    @DisplayName("删除会话：先删 MySQL 主数据，再通知 Python 清 checkpoint")
    void deleteRemovesThreadThenCleansCheckpoint() {
        when(mapper.selectOne(any())).thenReturn(thread("t1", "会话"));

        service.delete("t1");

        InOrder inOrder = inOrder(mapper, agentClient);
        inOrder.verify(mapper).deleteById("t1");
        inOrder.verify(agentClient).deleteThread("t1");
    }

    @Test
    @DisplayName("删除别人的会话：直接 404，不删主数据也不清 checkpoint")
    void deleteForeignThreadTouchesNothing() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.delete("t9"))
                .isInstanceOf(NotFoundException.class);

        verify(mapper, never()).deleteById(any(Serializable.class));
        verifyNoInteractions(agentClient);
    }

    // ---------------- helpers ----------------

    private static ChatThread thread(Object id, String title) {
        ChatThread thread = new ChatThread();
        thread.setId((String) id);
        thread.setUserId(ME);
        thread.setTitle(title);
        thread.setCreatedAt(LocalDateTime.of(2026, 11, 23, 9, 0));
        thread.setUpdatedAt(LocalDateTime.of(2026, 11, 23, 10, 0));
        return thread;
    }

    private static void loginAs(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("tester");
        user.setStatus(UserStatus.ACTIVE);
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities()));
    }
}