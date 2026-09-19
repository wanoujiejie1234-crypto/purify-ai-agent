package com.purify.purifyaiagent.tools;

import com.purify.purifyaiagent.model.ActivityLevel;
import com.purify.purifyaiagent.model.UserProfile;
import com.purify.purifyaiagent.profile.UserProfileRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户画像工具 + MySQL 落库的集成测试。
 *
 * <p>和 {@code SlimAppTest} 一样是「真调用」：会连本地 MySQL 读写 {@code user_profile}
 * 和 {@code user_profile_weight_history} 两张表。运行前确认：
 * <ul>
 *   <li>本地 MySQL 已启动（连接信息见 application-local.yml 的 spring.datasource）；</li>
 *   <li>建表脚本已经生效——由 application.yml 的 {@code spring.sql.init} 在启动时执行，
 *       启动日志里能看到它建表；手工执行 {@code db/user-profile-schema-mysql.sql} 也可以。</li>
 * </ul>
 * <b>不调用模型</b>：全程直接调工具的 Java 方法，所以跑起来是秒级的，也不消耗 token。
 *
 * <p>这里刻意<b>不走对话链路</b>（{@code slimApp.chat(...)} 那种），因为那样验的是
 * 「模型愿不愿意调这个工具」，是概率问题，会时绿时红；而这一段要锁住的是确定性的东西：
 * 存进去的能读回来、只改一个字段不会把别的冲掉。
 *
 * <p>用例自己造数据、用完自己删，用的是带随机后缀的 userId，不会碰到真实用户的画像。
 */
@Slf4j
@SpringBootTest
class UserProfileToolTest {

    @Resource
    private UserProfileTool userProfileTool;

    @Resource
    private UserProfileRepository userProfileRepository;

    @Test
    @DisplayName("画像存得下读得回，且只改体重不会把身高目标忌口一起冲掉")
    void updateThenGet_keepsFieldsNotMentioned() {
        // 每次跑都换一个人，避免和上一次的残留数据串味
        String userId = "test-profile-" + UUID.randomUUID();
        ToolContext context = new ToolContext(Map.of(UserProfileTool.USER_ID_KEY, userId));

        try {
            // 1. 没建过档时，工具要明确说「还没有」，而不是返回一段空白——
            //    模型看到空白会以为自己拿到了画像，然后拿空气当依据开始给建议
            String before = userProfileTool.getUserProfile(context);
            log.info("[updateThenGet] 建档前：{}", before);
            assertTrue(before.contains("还没有"), "没有画像时应当明说，实际：" + before);

            // 2. 第一轮：用户报了一部分信息，另一些（这里是年龄之外的）先不说
            String created = userProfileTool.updateUserProfile(32, 170.0, 71.5,
                    "三个月减到 65 公斤", ActivityLevel.MODERATE, "爱吃面食，不吃辣", "海鲜过敏", context);
            log.info("[updateThenGet] 建档：{}", created);
            assertTrue(created.contains("已保存"), "建档应当返回保存成功的确认，实际：" + created);

            // 3. 第二轮：用户只提了一句体重变了，其余字段一个都没传。
            //    全部传 null 是模型很自然的做法——它只传这轮听到的东西
            userProfileTool.updateUserProfile(null, null, 70.0, null, null, null, null, context);

            // 4. 读回来对账。这一步是本用例的重点：一个字段一个字段地确认
            //    「这轮没提到的」都还在。写成整体覆盖的实现在这里会立刻露馅——
            //    身高、目标、忌口会一起变成 null
            String profile = userProfileTool.getUserProfile(context);
            log.info("[updateThenGet] 更新后：{}", profile);

            assertTrue(profile.contains("32 岁"), "年龄不该被这轮没传它的更新冲掉：" + profile);
            assertTrue(profile.contains("170.0 cm"), "身高不该被冲掉：" + profile);
            assertTrue(profile.contains("70.0 kg"), "体重应当更新成最新值：" + profile);
            assertTrue(profile.contains("三个月减到 65 公斤"), "目标不该被冲掉：" + profile);
            assertTrue(profile.contains("中度活动"), "活动水平不该被冲掉：" + profile);
            assertTrue(profile.contains("爱吃面食，不吃辣"), "饮食偏好不该被冲掉：" + profile);
            assertTrue(profile.contains("海鲜过敏"), "忌口不该被冲掉：" + profile);

            // 5. 历史数据：两次不同的体重应当留下两条记录，并且给出趋势
            List<UserProfile.WeightRecord> history = userProfileRepository.findWeightHistory(userId);
            log.info("[updateThenGet] 体重流水：{}", history);
            assertEquals(2, history.size(), "两次不同体重应当记两条流水");
            assertEquals(70.0, history.get(0).weightKg(), "流水的第一条应当是最新的那次");
            assertTrue(profile.contains("71.5"), "历史里应当还能看到上一次的体重：" + profile);
            assertTrue(profile.contains("减少 1.5 kg"), "应当算出 71.5 → 70.0 的下降趋势：" + profile);

            // 6. 同一个体重再传一遍，不该又多出一条流水——
            //    模型把已知的体重重复传是很常见的，逐次照记的话趋势会被同一个数字淹没
            userProfileTool.updateUserProfile(null, null, 70.0, null, null, null, null, context);
            assertEquals(2, userProfileRepository.findWeightHistory(userId).size(),
                    "体重没变时不该再记一条");

        } finally {
            // 用例造的测试用户自己删干净，不往正式库里留垃圾
            userProfileRepository.delete(userId);
        }

        assertFalse(userProfileRepository.find(userId).isPresent(), "收尾删除后不该还能读到");
    }
}
