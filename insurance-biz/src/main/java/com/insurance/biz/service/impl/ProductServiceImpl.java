package com.insurance.biz.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.insurance.biz.common.exception.NotFoundException;
import com.insurance.biz.domain.dto.ProductCandidateQueryDTO;
import com.insurance.biz.domain.po.Product;
import com.insurance.biz.domain.vo.ProductPageVO;
import com.insurance.biz.domain.vo.ProductVO;
import com.insurance.biz.mapper.ProductMapper;
import com.insurance.biz.service.IProductService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product>
        implements IProductService {

    /** products.status 的取值 */
    private static final String STATUS_ON_SALE = "active";

    /** 单页上限：防止前端传 999999 把整张表拉出来 */
    private static final long MAX_PAGE_SIZE = 50;

    @Override
    public List<ProductVO> listOnSale() {
        List<Product> products = lambdaQuery()
                .eq(Product::getStatus, STATUS_ON_SALE)
                .orderByDesc(Product::getId)
                .list();

        return products.stream().map(this::toVO).toList();
    }

    @Override
    public ProductPageVO pageOnSale(String category, long page, long pageSize) {
        // 参数兜底：非法值在这里收敛，不依赖调用方
        long current = Math.max(page, 1);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        Page<Product> result = page(
                new Page<>(current, size),
                Wrappers.<Product>lambdaQuery()
                        .eq(Product::getStatus, STATUS_ON_SALE)
                        // 第一个参数是开关：category 为空时不加这个条件
                        .eq(StringUtils.hasText(category), Product::getCategory, category)
                        .orderByDesc(Product::getId));

        List<ProductVO> items = result.getRecords().stream().map(this::toVO).toList();
        return new ProductPageVO(
                items,
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages());
    }

    @Override
    public List<ProductVO> listCandidates(ProductCandidateQueryDTO query) {
        int limit = query.limitOrDefault();

        List<ProductVO> result = new ArrayList<>();
        // 去重但保留传入顺序（对应 Python 侧的 dict.fromkeys）
        for (String category : new LinkedHashSet<>(query.categories())) {
            List<Product> products = lambdaQuery()
                    .eq(Product::getStatus, STATUS_ON_SALE)
                    .eq(Product::getCategory, category)
                    // premiumMin 为 null 时这个条件不生效（第一个参数是开关）
                    .lt(query.premiumMin() != null, Product::getMinPremium, query.premiumMin())
                    // MySQL 没有 NULLS LAST：升序默认把 NULL 排最前，
                    // 先用 min_premium IS NULL 把空值推到后面，再按保费升序
                    .last("ORDER BY min_premium IS NULL, min_premium ASC, id ASC LIMIT " + limit)
                    .list();

            products.stream().map(this::toVO).forEach(result::add);
        }
        return result;
    }

    @Override
    public ProductVO getDetail(Long id) {
        Product product = getById(id);
        if (product == null) {
            throw new NotFoundException("产品不存在");
        }
        return toVO(product);
    }

    /** po → vo：只拷前端需要的字段，同名字段直接复制 */
    private ProductVO toVO(Product product) {
        ProductVO vo = new ProductVO();
        BeanUtils.copyProperties(product, vo);
        return vo;
    }
}