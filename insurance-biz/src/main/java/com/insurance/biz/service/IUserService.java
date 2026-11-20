package com.insurance.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.insurance.biz.domain.dto.LoginFormDTO;
import com.insurance.biz.domain.dto.RegisterFormDTO;
import com.insurance.biz.domain.po.User;
import com.insurance.biz.domain.vo.UserLoginVO;

/**
 * 用户相关业务。
 * <p>
 * 继承 IService 是为了拿到 save / getById / lambdaQuery 这些通用方法，
 * 不用在实现类里再注入一遍 Mapper。
 */
public interface IUserService extends IService<User> {

    /** 注册并直接返回 token，省掉一次登录请求 */
    UserLoginVO register(RegisterFormDTO dto);

    /** 登录：校验凭证并签发 token */
    UserLoginVO login(LoginFormDTO dto);
}
