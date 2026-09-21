package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.auth.CurrentUser;
import com.purify.purifyaiagent.auth.LoginUser;
import com.purify.purifyaiagent.auth.RequireLogin;
import com.purify.purifyaiagent.model.UserProfile;
import com.purify.purifyaiagent.model.UserProfileUpdateRequest;
import com.purify.purifyaiagent.model.UserProfileView;
import com.purify.purifyaiagent.profile.ProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户画像的自助读写，给设置页用。
 *
 * <pre>
 *   GET /api/profile   读当前用户的画像
 *   PUT /api/profile   改（只传要改的字段）
 * </pre>
 *
 * <p><b>在这之前，画像是只能通过对话改的。</b>唯一的写入口是
 * {@code UserProfileTool.updateUserProfile}——也就是说用户想改身高，
 * 得去聊天里跟模型说一句「我 175」。设置页要能直接填表，
 * 就有了这条路径。
 *
 * <p>两条路径共用 {@link ProfileService}，所以「先读再合并」「体重变了才记流水」
 * 这些规则不会走偏。这里**不做任何额外的合并或校验**，转手就交给 service——
 * 在这层再判断一次，就等于把规则抄了第二遍。
 */
@Slf4j
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 读画像。
     *
     * <p>没有画像时返回一份空画像而不是 404：对设置页来说「还没填过」和「填了但都是空的」
     * 是同一种状态——表单显示空白。返 404 的话前端每打开一次抽屉都要先处理一次错误，
     * 而那不是错误。
     */
    @GetMapping
    @RequireLogin
    public UserProfileView read(@CurrentUser LoginUser me) {
        UserProfile profile = profileService.read(me.id());
        return UserProfileView.from(profile, profileService.history(me.id()));
    }

    /**
     * 改画像。**整体替换**：表单上是什么，库里就是什么。
     *
     * <p>用替换而不是合并，是为了让用户能<b>清掉</b>一个字段。合并语义把空值当成
     * 「这次没提」，于是忌口填错了想删掉、删完保存回来看还在——那种 bug 很难解释。
     * 代价是前端每次必须把七个字段都发上来，只发改动过的会把其余的当成清空。
     *
     * <p>唯一的例外是<b>七个字段全是 null</b>：那是「请求里什么都没有」，
     * 更像前端拼错了请求体，而不是「用户想把画像清空」。这种情况按无变化处理、
     * 原样返回当前画像——总比一个字都没动却把用户填过的资料全抹掉强。
     *
     * <p>返回改完之后<b>完整</b>的画像，前端可以直接拿它刷新表单，不用再 GET 一次。
     */
    @PutMapping
    @RequireLogin
    public UserProfileView update(@RequestBody UserProfileUpdateRequest request,
                                  @CurrentUser LoginUser me) {
        UserProfile incoming = request.toProfile();
        if (incoming.isBlank()) {
            log.debug("[Profile] userId={} 请求里一个字段都没有，按无变化处理", me.id());
            return UserProfileView.from(profileService.read(me.id()), profileService.history(me.id()));
        }

        UserProfile saved = profileService.replace(me.id(), incoming);
        log.info("[Profile] userId={} 通过设置页更新了画像", me.id());
        return UserProfileView.from(saved, profileService.history(me.id()));
    }
}
