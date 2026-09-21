package com.purify.purifyaiagent.model;

import com.purify.purifyaiagent.exception.ApiException;

/**
 * 对话接口的请求体。轻语和 PurifyManus 共用同一个形状，前端的发送逻辑只差一个 URL。
 *
 * <p>用 POST + JSON 而不是 GET + query 参数：{@code message} 是用户随手打的一段话，
 * 走 URL 要先编码、长一点就撞上长度上限，排查时看到的还只是一串 {@code %E4%B8%AD}。
 *
 * @param chatId  会话 ID。不传或传空时由服务端新建一个，并在响应头 {@code X-Chat-Id} 里返回，
 *                所以第一次对话不需要先申请 ID；后续请求带上同一个 ID 就能接着上文。
 *                智能体那边它还有第二层含义：这也是「回答它上一轮提问」的方式
 * @param message 用户说的话，不能为空
 */
public record ChatRequest(String chatId, String message) {

    /**
     * 取出用户输入，为空则拒绝。
     *
     * <p>规则放在这个类型上而不是各写一份在 controller 里：两条链路的校验必须一致，
     * 而分开写就一定会有一天只改了一边——那时候用户会看到「轻语这边拦了、智能体那边没拦」。
     *
     * <p>空消息必须在入口挡掉：让它走到模型那一层，DashScope 会报一个含义模糊的参数错误，
     * 看起来像服务端故障，而它其实是一次客户端传错。
     */
    public String requireMessage() {
        if (message == null || message.isBlank()) {
            throw ApiException.invalidChatRequest("error.chat.messageRequired");
        }
        return message;
    }
}
