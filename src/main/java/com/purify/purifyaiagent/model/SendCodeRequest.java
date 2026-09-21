package com.purify.purifyaiagent.model;

import com.purify.purifyaiagent.auth.VerificationPurpose;
import com.purify.purifyaiagent.exception.ApiException;

/**
 * 请求发送邮箱验证码。
 *
 * @param email   收件邮箱
 * @param purpose {@code REGISTER} 或 {@code RESET_PASSWORD}。
 *                <b>必填且必须能认出来</b>——认不出来时报 400，不猜一个默认值：
 *                猜成注册的话，一个拼错的 purpose 会发出一条用途不对的验证码，
 *                而用户在下一步只会看到「验证码不正确」，没有任何线索指向这里
 */
public record SendCodeRequest(String email, String purpose) {

    /**
     * 取出用途，认不出来就拒绝。
     *
     * <p>校验放在记录上而不是 controller 里，和 {@code ChatRequest#requireMessage()} 同样的理由：
     * 这个规则只有一个地方定义，将来加第二个入口时不会漏。
     */
    public VerificationPurpose requirePurpose() {
        VerificationPurpose parsed = VerificationPurpose.parse(purpose);
        if (parsed == null) {
            throw ApiException.authInvalid("error.auth.unknownPurpose", purpose);
        }
        return parsed;
    }
}
