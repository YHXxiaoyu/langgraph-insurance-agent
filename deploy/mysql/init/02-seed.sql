-- ============================================================
-- 安心保 业务库初始数据
-- 对应 PG 的 V1 / V3 / V4 / V5 / V6 / V12 种子脚本
-- 全部使用 INSERT IGNORE，重复执行不会报错
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 一、基础数据
-- ------------------------------------------------------------

INSERT IGNORE INTO app_metadata (`key`, value)
VALUES ('schema', 'business-service-baseline');

-- 演示用户
-- 注意：原 hash 是占位符，不是有效 BCrypt，登录前需替换
INSERT IGNORE INTO users (id, username, email, password_hash, display_name, status)
VALUES (1, 'demo', 'demo@example.com',
        '$2a$12$demo.password.hash.placeholder',
        '演示用户', 'active');

-- 产品（12 条），highlights 由 PG 的 ARRAY[...] 改为 JSON 数组
INSERT IGNORE INTO products
    (id, name, clause_name, category, insurer, image_url, description,
     min_premium, max_premium, target_group, highlights)
VALUES
(1, '达尔文宝贝计划12号', '信美相互星辰守护B款少儿重疾险条款.pdf', 'critical_illness', '信美人寿相互保险社',
 'https://files.huizecdn.com/file3/M00/91/12/rBYA3Gi1gZ2AXBL7AAYFJxRhbPg411.png',
 '117种重疾+28种中症+45种轻症，少儿重疾保障灵活。', 285.30, 2501.83, '0-17周岁',
 '["117种重疾","少儿保障","可选投保人豁免"]'),

(2, '达尔文12号重大疾病保险', '复星联合优选K重疾险(达尔文12号)条款.pdf', 'critical_illness', '复星联合健康保险',
 'https://files.huizecdn.com/file3/M00/90/E5/rBYA3Gi1BnWALIBtAAYFAsRjzwk030.png',
 '120种重疾+30种中症+45种轻症，可选多项责任。', 561.00, 4815.00, '28天-35周岁（1-4类职业）',
 '["120种重疾","中轻症保障","可选责任丰富"]'),

(3, '复星联合医联有盟重疾险', '复星联合医联有盟重疾险条款.pdf', 'critical_illness', '复星联合健康保险',
 'https://files.huizecdn.com/file3/M00/95/DE/rBYA3GjL0m-AN888AAEX8uDe8d4708.png',
 '重疾保障产品，含主险及长期医疗附加保障。', 3185.00, 5111.00, '18-59周岁（1-4类职业）',
 '["重疾保障","可附加医疗"]'),

(4, '复星联合星相守2号长期医疗险（个人版）', '复星联合优选二号长期住院医疗险(星相守2号)条款.pdf', 'medical', '复星联合健康保险',
 'https://files.huizecdn.com/file3/M00/AC/B9/rBYA3GlFEUWAWcX8AAO1o6m75Qg526.png',
 '免赔额灵活可选，保证续保20年特需，外购药不限清单。', 148.00, 3605.00, '1-55周岁（1-4类职业）',
 '["长期医疗","门急诊可选","家庭保障"]'),

(5, '复星联合星相守2号长期医疗险（转保版）', '复星联合优选二号长期住院医疗险(星相守2号)条款.pdf', 'medical', '复星联合健康保险',
 'https://files.huizecdn.com/file3/M00/AC/B9/rBYA3GlFEUWAWcX8AAO1o6m75Qg526.png',
 '免赔额灵活可选，保证续保20年特需，外购药不限清单。', 180.00, 4409.00, '1-70周岁（1-4类职业）',
 '["转保场景","0免赔","特需医疗"]'),

(6, '众安尊享e生百万医疗险2026版', '众安尊享e生百万医疗险2026版条款.pdf', 'medical', '众安保险',
 'https://files.huizecdn.com/file3/M00/B0/02/rBYA3GlTLeGAGS9sAAEwUtrU9VE280.png',
 '百万医疗险产品，覆盖住院、特殊门诊等医疗责任。', 255.00, 1420.00, '30天-70周岁（1-6类职业）',
 '["百万医疗","医疗保障","责任免除清晰"]'),

(7, '中英人寿福满佳C款（悦享版）', '中英人寿福满佳C款(悦享版)终身寿险分红型条款.pdf', 'life', '中英人寿',
 'https://files.huizecdn.com/file3/M00/C1/9A/rBYA3GmJR8qAMR8dAAEJXoRqbAU476.png',
 '成长型分红险，更高预期收益。', 10000.00, NULL, '30天-57周岁（1-6类职业）',
 '["终身寿险","分红型","长期保障"]'),

(8, '太保福有余2025', '太平洋福有余2025终身寿险分红型条款.pdf', 'life', '太平洋人寿',
 'https://files.huizecdn.com/file3/M00/AA/6B/rBYA3Gk34AKAZAQCAAEf3uIE2EQ251.png',
 '终身寿险分红型产品。', 5000.00, NULL, '5天-68周岁（1-6类职业）',
 '["终身寿险","分红型"]'),

(9, '新华人寿E增福优享版', '新华人寿E增福优享版终身寿险互联网条款.pdf', 'life', '新华人寿',
 'https://files.huizecdn.com/file3/M00/8E/92/rBYA3GitLDaAPo2fAADxu5ximjs300.png',
 '互联网终身寿险产品。', 10000.00, NULL, '0-70周岁（1-6类职业）',
 '["终身寿险","互联网产品"]'),

(10, '太平洋小蜜蜂6号综合意外险', '太平洋小蜜蜂6号综合意外险条款.pdf', 'accident', '太平洋财险',
 'https://files.huizecdn.com/file3/M00/97/98/rBYA3GjUtNOASrCtAAP85DM8wvE838.png',
 '综合意外险，含意外伤害及可选附加医疗、猝死责任。', 136.00, 298.00, '18-50周岁（1-3类职业）',
 '["综合意外","意外医疗","猝死附加"]'),

(11, '太平洋小蜜蜂6号玫瑰版综合意外险', '太平洋小蜜蜂6号综合意外险条款.pdf', 'accident', '太平洋财险',
 'https://files.huizecdn.com/file3/M00/97/9A/rBYA3GjUuyyAHuBnAAQ7pqK0eTo763.png',
 '女性场景综合意外险，与标准版共享基础条款。', 169.00, 439.00, '18-50周岁（1-3类职业）',
 '["综合意外","女性场景","共享条款"]'),

(12, '人保小学童2号Pro学平险', '人保小学童2号Pro学平险学生幼儿意外伤害条款.pdf', 'accident', '中国人保财险',
 'https://files.huizecdn.com/file3/M00/8A/2C/rBYA3Gia2NCAPoeMAAOJtnYK38s337.png',
 '学生幼儿综合保障，含意外、住院、重疾等附加责任。', 240.00, 335.00, '3-24周岁',
 '["学平险","意外保障","住院医疗","重疾附加"]');

-- ------------------------------------------------------------
-- 二、费率表
-- ------------------------------------------------------------

-- 原 PG 中 id 为 ...001 / ...004 / ...005 的三条在 V5 里被置为 active，这里直接写最终状态
INSERT IGNORE INTO rate_table_files (id, product_id, file_name, file_path, status) VALUES
('10000000-0000-0000-0000-000000000001', 2,  '复星联合优选K重疾险(达尔文12号)费率表.pdf',            'data/raw/rate_tables/复星联合优选K重疾险(达尔文12号)费率表.pdf',            'active'),
('10000000-0000-0000-0000-000000000002', 3,  '复星联合医联有盟重疾险费率表.pdf',                        'data/raw/rate_tables/复星联合医联有盟重疾险费率表.pdf',                        'pending'),
('10000000-0000-0000-0000-000000000003', 4,  '复星联合优选二号长期住院医疗险(星相守2号)费率表.pdf',      'data/raw/rate_tables/复星联合优选二号长期住院医疗险(星相守2号)费率表.pdf',      'pending'),
('10000000-0000-0000-0000-000000000004', 6,  '众安尊享e生百万医疗险2026版费率表.pdf',                    'data/raw/rate_tables/众安尊享e生百万医疗险2026版费率表.pdf',                    'active'),
('10000000-0000-0000-0000-000000000005', 10, '太平洋小蜜蜂6号综合意外险费率表.pdf',                      'data/raw/rate_tables/太平洋小蜜蜂6号综合意外险费率表.pdf',                      'active'),
('10000000-0000-0000-0000-000000000006', 11, '太平洋小蜜蜂6号玫瑰版综合意外险费率表.pdf',                'data/raw/rate_tables/太平洋小蜜蜂6号玫瑰版综合意外险费率表.pdf',                'pending'),
('10000000-0000-0000-0000-000000000007', 12, '人保小学童2号Pro学平险费率表.pdf',                         'data/raw/rate_tables/人保小学童2号Pro学平险费率表.pdf',                         'pending'),
('10000000-0000-0000-0000-000000000008', 7,  '中英人寿福满佳C款(悦享版)终身寿险分红型费率表.pdf',        'data/raw/rate_tables/中英人寿福满佳C款(悦享版)终身寿险分红型费率表.pdf',        'pending'),
('10000000-0000-0000-0000-000000000009', 8,  '太平洋福有余2025终身寿险分红型费率表.pdf',                 'data/raw/rate_tables/太平洋福有余2025终身寿险分红型费率表.pdf',                 'pending'),
('10000000-0000-0000-0000-000000000010', 9,  '新华人寿E增福优享版终身寿险互联网费率表.pdf',              'data/raw/rate_tables/新华人寿E增福优享版终身寿险互联网费率表.pdf',              'pending');

INSERT IGNORE INTO rate_plans
    (id, product_id, plan_code, plan_name, rate_type, required_params, optional_params, status)
VALUES
('20000000-0000-0000-0000-000000000001', 2, 'standard', '标准责任', 'per_10000_sum_assured',
 '["age","gender","coverage_amount","payment_period","coverage_period"]',
 '["occupation_class","social_security"]', 'active'),

('20000000-0000-0000-0000-000000000002', 6, 'standard', '标准计划', 'age_band_plan_premium',
 '["age","social_security","deductible"]',
 '["gender","occupation_class"]', 'active'),

('20000000-0000-0000-0000-000000000003', 10, 'standard', '标准计划', 'fixed_plan_premium',
 '["coverage_amount","occupation_class"]',
 '["age","gender"]', 'active');

INSERT IGNORE INTO rate_table_items
    (id, rate_file_id, product_id, plan_code, rate_type,
     age_min, age_max, gender, occupation_class, social_security,
     payment_period, coverage_period, coverage_amount, deductible,
     dimensions, rate_value, premium, source_page, source_table, status)
VALUES
('30000000-0000-0000-0000-000000000001',
 '10000000-0000-0000-0000-000000000001', 2, 'standard', 'per_10000_sum_assured',
 30, 30, 'female', 'all', 'all',
 '20y', 'lifetime', NULL, NULL,
 '{"note":"manual sample from structured rate table pipeline"}',
 78.000000, NULL, 3, '女性30岁20年交终身费率样例', 'active'),

('30000000-0000-0000-0000-000000000002',
 '10000000-0000-0000-0000-000000000001', 2, 'standard', 'per_10000_sum_assured',
 30, 30, 'male', 'all', 'all',
 '20y', 'lifetime', NULL, NULL,
 '{"note":"manual sample from structured rate table pipeline"}',
 86.000000, NULL, 3, '男性30岁20年交终身费率样例', 'active'),

('30000000-0000-0000-0000-000000000003',
 '10000000-0000-0000-0000-000000000004', 6, 'standard', 'age_band_plan_premium',
 0, 30, 'all', 'all', 'yes',
 'annual', '1y', 1000000.00, 10000.00,
 '{"note":"manual sample from structured rate table pipeline"}',
 NULL, 350.00, 2, '有社保1万免赔0-30岁样例', 'active'),

('30000000-0000-0000-0000-000000000004',
 '10000000-0000-0000-0000-000000000004', 6, 'standard', 'age_band_plan_premium',
 31, 50, 'all', 'all', 'yes',
 'annual', '1y', 1000000.00, 10000.00,
 '{"note":"manual sample from structured rate table pipeline"}',
 NULL, 600.00, 2, '有社保1万免赔31-50岁样例', 'active'),

('30000000-0000-0000-0000-000000000005',
 '10000000-0000-0000-0000-000000000004', 6, 'standard', 'age_band_plan_premium',
 51, 70, 'all', 'all', 'yes',
 'annual', '1y', 1000000.00, 10000.00,
 '{"note":"manual sample from structured rate table pipeline"}',
 NULL, 1200.00, 2, '有社保1万免赔51-70岁样例', 'active'),

('30000000-0000-0000-0000-000000000006',
 '10000000-0000-0000-0000-000000000005', 10, 'standard', 'fixed_plan_premium',
 NULL, NULL, 'all', '1', 'all',
 'annual', NULL, 500000.00, NULL,
 '{"note":"manual sample from structured rate table pipeline"}',
 NULL, 136.00, 1, '综合意外标准计划样例', 'active');

-- ------------------------------------------------------------
-- 三、试算适配器与 Schema
-- ------------------------------------------------------------

INSERT IGNORE INTO insurer_api_adapters
    (id, insurer_code, adapter_type, adapter_name, config, status)
VALUES
('41000000-0000-0000-0000-000000000001', 'mock_fosun',   'mock', '复星联合 mock 试算适配器',   '{}', 'active'),
('41000000-0000-0000-0000-000000000002', 'mock_zhongan', 'mock', '众安保险 mock 试算适配器',   '{}', 'active'),
('41000000-0000-0000-0000-000000000003', 'mock_cpci',    'mock', '太平洋财险 mock 试算适配器', '{}', 'active');

INSERT IGNORE INTO product_pricing_schemas
    (id, product_id, scenario, schema_version, schema_json, schema_hash, status, activated_at)
VALUES
('40000000-0000-0000-0000-000000000001', 2, 'premium_by_coverage', '2026-06-09.v1',
 '{
   "title": "达尔文12号重大疾病保险试算",
   "fields": [
     {"key": "plan_code", "label": "保障计划", "type": "plan_select", "required": true, "options_ref": "plan_options"},
     {"key": "insured.age", "label": "被保人年龄", "type": "integer", "required": true, "min": 0, "max": 35},
     {"key": "insured.gender", "label": "被保人性别", "type": "select", "required": true, "options": [{"value": "male", "label": "男"}, {"value": "female", "label": "女"}]},
     {"key": "coverage.coverage_amount", "label": "基本保险金额", "type": "money", "required": true, "options": [300000, 500000, 1000000]},
     {"key": "coverage.payment_period", "label": "缴费年限", "type": "select", "required": true, "options": [{"value": "10y", "label": "10年"}, {"value": "20y", "label": "20年"}, {"value": "30y", "label": "30年"}]},
     {"key": "coverage.coverage_period", "label": "保障期限", "type": "select", "required": true, "options": [{"value": "lifetime", "label": "终身"}]}
   ],
   "plan_options": [
     {"value": "basic", "label": "基础责任"},
     {"value": "classic", "label": "经典责任"},
     {"value": "premium", "label": "尊享责任"}
   ],
   "rules": []
 }', NULL, 'active', NOW()),

('40000000-0000-0000-0000-000000000002', 6, 'premium_by_coverage', '2026-06-09.v1',
 '{
   "title": "众安尊享e生百万医疗险2026版试算",
   "fields": [
     {"key": "plan_code", "label": "保障计划", "type": "plan_select", "required": true, "options_ref": "plan_options"},
     {"key": "insured.age", "label": "被保人年龄", "type": "integer", "required": true, "min": 0, "max": 70},
     {"key": "insured.social_security", "label": "是否有社保", "type": "select", "required": true, "options": [{"value": "yes", "label": "有"}, {"value": "no", "label": "无"}]},
     {"key": "coverage.deductible", "label": "免赔额", "type": "money", "required": true, "options": [0, 10000]},
     {"key": "coverage.coverage_amount", "label": "一般医疗保额", "type": "money", "required": true, "options": [1000000, 2000000]}
   ],
   "plan_options": [
     {"value": "standard", "label": "标准版"},
     {"value": "plus", "label": "升级版"}
   ],
   "rules": []
 }', NULL, 'active', NOW()),

('40000000-0000-0000-0000-000000000003', 10, 'premium_by_coverage', '2026-06-09.v1',
 '{
   "title": "太平洋小蜜蜂6号综合意外险试算",
   "fields": [
     {"key": "plan_code", "label": "保障计划", "type": "plan_select", "required": true, "options_ref": "plan_options"},
     {"key": "insured.age", "label": "被保人年龄", "type": "integer", "required": true, "min": 18, "max": 65},
     {"key": "insured.occupation_class", "label": "承保职业", "type": "select", "required": true, "options": [{"value": "1-4", "label": "1-4类"}, {"value": "5-6", "label": "5-6类"}]},
     {"key": "coverage.coverage_amount", "label": "意外身故/伤残保额", "type": "money", "required": true, "options": [300000, 500000, 1000000]}
   ],
   "plan_options": [
     {"value": "basic", "label": "基础版", "description": "低保费基础保障"},
     {"value": "classic", "label": "经典版", "description": "均衡保障"},
     {"value": "premium", "label": "尊享版", "description": "高额保障"}
   ],
   "rules": []
 }', NULL, 'active', NOW());

INSERT IGNORE INTO product_pricing_bindings
    (id, product_id, pricing_schema_id, adapter_id,
     external_product_code, api_code, field_mapping, response_mapping, mock_config, status)
VALUES
('42000000-0000-0000-0000-000000000001', 2,
 '40000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001',
 'FOSUN_DW12', 'mock_premium_trial_v1',
 '{"plan_code":"packageCode","insured.age":"age","insured.gender":"gender","coverage.coverage_amount":"sumInsured","coverage.payment_period":"paymentPeriod","coverage.coverage_period":"coveragePeriod"}',
 '{}',
 '{"base_premium_by_plan":{"basic":500,"classic":700,"premium":950},"age_factor":[{"min":0,"max":17,"factor":0.8},{"min":18,"max":35,"factor":1.0}],"gender_factor":{"male":1.1,"female":1.0},"coverage_unit":500000,"coverage_factor_enabled":true}',
 'active'),

('42000000-0000-0000-0000-000000000002', 6,
 '40000000-0000-0000-0000-000000000002', '41000000-0000-0000-0000-000000000002',
 'ZA_ZXES_2026', 'mock_premium_trial_v1',
 '{"plan_code":"packageCode","insured.age":"age","insured.social_security":"hasSocialSecurity","coverage.deductible":"deductible","coverage.coverage_amount":"sumInsured"}',
 '{}',
 '{"base_premium_by_plan":{"standard":300,"plus":460},"age_factor":[{"min":0,"max":30,"factor":1.0},{"min":31,"max":50,"factor":1.6},{"min":51,"max":70,"factor":3.0}],"social_security_factor":{"yes":1.0,"no":1.3},"deductible_factor":{"0":1.5,"10000":1.0},"coverage_unit":1000000,"coverage_factor_enabled":true}',
 'active'),

('42000000-0000-0000-0000-000000000003', 10,
 '40000000-0000-0000-0000-000000000003', '41000000-0000-0000-0000-000000000003',
 'CPCI_XMF6', 'mock_premium_trial_v1',
 '{"plan_code":"packageCode","insured.age":"age","insured.occupation_class":"occupationClass","coverage.coverage_amount":"sumInsured"}',
 '{}',
 '{"base_premium_by_plan":{"basic":120,"classic":180,"premium":260},"age_factor":[{"min":18,"max":45,"factor":1.0},{"min":46,"max":65,"factor":1.4}],"occupation_factor":{"1-4":1.0,"5-6":1.8},"coverage_unit":500000,"coverage_factor_enabled":true}',
 'active');

-- ------------------------------------------------------------
-- 四、保单与理赔
-- ------------------------------------------------------------

INSERT IGNORE INTO policies
    (id, user_id, product_id, policy_number, status, effective_at, expires_at)
VALUES
('10000000-0000-0000-0000-000000000001', 1, 6, 'POL-DEMO-2026-0001', 'active',
 DATE_SUB(NOW(), INTERVAL 120 DAY), DATE_ADD(NOW(), INTERVAL 245 DAY)),
('10000000-0000-0000-0000-000000000002', 1, 10, 'POL-DEMO-2024-0002', 'expired',
 DATE_SUB(NOW(), INTERVAL 730 DAY), DATE_SUB(NOW(), INTERVAL 365 DAY));

INSERT IGNORE INTO claims
    (id, user_id, policy_id, product_id, claim_number, claim_type,
     amount, description, status, submitted_at, updated_at)
VALUES
('20000000-0000-0000-0000-000000000001', 1,
 '10000000-0000-0000-0000-000000000001', 6, 'CLM-DEMO-2026-0001', 'medical',
 NULL, '住院医疗费用理赔材料审核中', 'reviewing',
 DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),

('20000000-0000-0000-0000-000000000002', 1,
 '10000000-0000-0000-0000-000000000002', 10, 'CLM-DEMO-2025-0002', 'accident',
 1200.00, '意外医疗理赔已完成赔付', 'paid',
 DATE_SUB(NOW(), INTERVAL 400 DAY), DATE_SUB(NOW(), INTERVAL 390 DAY));
