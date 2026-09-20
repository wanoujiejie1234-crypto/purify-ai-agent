package com.purify.purifyaiagent.auth;

import com.purify.purifyaiagent.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 开机确保超级用户 {@code root_agent} 存在且可用。
 *
 * <p><b>为什么不能用 SQL 脚本插入</b>：密码列存的是 BCrypt 哈希，而 BCrypt 的盐是每次随机生成的，
 * 所以那个哈希只能在运行期算出来。写死在脚本里的哈希要么是别人的盐（不安全），
 * 要么就得把明文口令固定在仓库里——而明文本来就已经在 {@code application.yml} 里了，
 * 不必再多存一份哈希。
 *
 * <p><b>执行时机</b>：{@code ApplicationRunner} 跑在 {@code spring.sql.init} 之后
 * （后者是 {@code DataSource} 初始化的一部分），所以表一定已经建好了。
 * {@code @Order(2)} 让它排在 {@link SchemaGuard}（{@code @Order(1)}）后面——
 * 那个类负责补上用户名唯一索引，而下面那次「按用户名查」的正确性依赖它。
 *
 * <h3>两个必须讲清楚的行为</h3>
 *
 * <p><b>1. 每次启动都重置密码、恢复 SUPER 角色、解除禁用和软删。</b>
 * 也就是说，在应用里改了 {@code root_agent} 的密码或角色，下次重启都会被还原。
 * 这不是疏忽，是刻意选的：如果种子写成「不存在才插入」，那么一旦有人把这个账号
 * 删掉、禁用或降级（不管是误操作还是故意的），这个系统就<b>再也回不去管理端了</b>——
 * 没有别的途径能造出一个 SUPER 用户，而知识库那几个接口只对它开放。
 * 开机复活是唯一的恢复手段。代价是这些改动不持久，所以每次启动都会打一条 WARN 提醒。
 *
 * <p>因为每次都用新的随机盐，这一行的哈希值每次启动都不一样。这是 BCrypt 的正常行为
 * （同样密码两次哈希结果不同），不需要担心；只是如果有什么东西在做行级哈希比对，会看到差异。
 *
 * <p><b>2. 查询不过滤 {@code is_deleted}。</b>见 {@link UserRepository#findByUsernameIncludingDeleted}：
 * 用户名还占着唯一索引，所以「查不到就插一条新的」会在插入时撞车、启动直接失败。
 * 必须看见被软删的那一行，才能走「把它复活」这条路。
 *
 * <p>口令来自 {@code purify.auth.root-agent.password}，明文写在入库的
 * {@code application.yml} 里，且标了 TODO。这个项目是本地运行的演示项目，
 * 可以接受；真要部署的话那是第一件该挪走的东西。
 */
@Slf4j
@Order(2)
public class RootAgentInitializer implements ApplicationRunner {

    private static final String NICKNAME = "超级管理员";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    public RootAgentInitializer(UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = authProperties.getRootAgent().getUsername();
        String password = authProperties.getRootAgent().getPassword();

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            // 不抛异常：配空了只是「没有超级用户」，不该让整个应用起不来。
            // 但要说清楚后果，否则表现是「知识库入口不见了，而且怎么都找不到原因」
            log.warn("[RootAgent] 没有配置超级用户的用户名或口令"
                    + "（purify.auth.root-agent.username / password），本次启动不会创建它。"
                    + "这意味着没有人能访问知识库管理接口。");
            return;
        }

        String hash = passwordEncoder.encode(password);
        Optional<UserAccount> existing = userRepository.findByUsernameIncludingDeleted(username);

        if (existing.isEmpty()) {
            Long id = userRepository.insert(username, hash, null, NICKNAME, UserRole.SUPER);
            log.info("[RootAgent] 已创建超级用户 {}（id={}）", username, id);
        }
        else {
            UserAccount account = existing.get();
            // 之前是什么状态，日志里要看得出来。这几种情况都可能是「有人动过手」，
            // 而这条日志是唯一的线索
            String was = account.isDeleted() ? "已被删除" : account.enabled() ? "正常" : "已被禁用";
            if (account.role() != UserRole.SUPER) {
                log.warn("[RootAgent] {} 的角色是 {} 而不是 SUPER，本次启动会把它改回 SUPER",
                        username, account.role());
            }
            userRepository.reviveAsSuper(account.id(), hash);
            log.info("[RootAgent] 已重置超级用户 {}（id={}，重置前的状态：{}，角色 {}）",
                    username, account.id(), was, account.role());
        }

        // 这条 WARN 每次启动都打，是有意的：这个行为（开机重置密码）不显眼，
        // 而它带来的后果（应用内改的密码不生效）很容易被当成 bug 查半天
        log.warn("[RootAgent] 注意：超级用户的口令来自配置（purify.auth.root-agent.password），"
                + "每次启动都会重置。在应用里改它的密码，下次重启会被还原。");
    }
}
