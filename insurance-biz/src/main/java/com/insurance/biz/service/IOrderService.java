package com.insurance.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.insurance.biz.domain.dto.CreateOrderDTO;
import com.insurance.biz.domain.po.Order;
import com.insurance.biz.domain.vo.OrderVO;

import java.util.List;

/** 投保订单相关业务。 */
public interface IOrderService extends IService<Order> {

    /** 从方案明细下单，状态为待支付；重复下单返回同一张订单 */
    OrderVO create(CreateOrderDTO dto);

    /** 支付：订单转为已支付，同时把方案项标记已投保并发保单；重复调用幂等 */
    OrderVO pay(String orderId);

    /** 取消：只有待支付订单能取消；取消后释放幂等键，可以重新下单 */
    OrderVO cancel(String orderId);

    /** 当前用户的订单列表，按下单时间倒序 */
    List<OrderVO> listMine();

    /** 当前用户的订单详情，不存在或不属于自己都抛 404 */
    OrderVO getMine(String orderId);
}