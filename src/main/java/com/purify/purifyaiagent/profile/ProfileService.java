package com.purify.purifyaiagent.profile;

import com.purify.purifyaiagent.model.UserProfile;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户画像的读写。
 *
 * <p><b>抽出来的理由不是「少写几行」，是两条写入路径必须共用同一套落库规则。</b>
 * 画像有两个入口：对话里的 {@code UserProfileTool}（模型听到什么就存什么），
 * 和设置页的 {@code ProfileController}（用户自己填表）。如果各写一份，很快就会出现
 * 「从设置页改的体重不进体重流水」这类问题——而且不会有任何报错。
 *
 * <p><b>但两边的合并语义是不一样的，这一点必须分清：</b>
 * <ul>
 *   <li>{@link #update} —— <b>合并</b>。给对话用。模型每轮只会听到一两个字段，
 *       没提的必须保持原值，所以走 {@link UserProfile#merge} 的「非空覆盖」；</li>
 *   <li>{@link #replace} —— <b>整体替换</b>。给设置页用。表单上显示的就是全部内容，
 *       保存时写回去的就该是全部内容。</li>
 * </ul>
 * 为什么设置页不能也用合并：合并把「空」当成「没填」，于是<b>用户永远清不掉一个字段</b>。
 * 忌口填错了想删掉，勾掉再保存，回来一看还在——因为空值被当成了「这次没提」。
 * 整体替换没有这个问题：表单上是什么，库里就是什么。
 *
 * <p>两条路径共用的部分是 {@link #persist}：盖时间戳、写库、按需追加体重流水。
 * 「体重真的变了才记流水」这条规则只写一遍。
 */
@Slf4j
public class ProfileService {

    private final UserProfileRepository repository;

    public ProfileService(UserProfileRepository repository) {
        this.repository = repository;
    }

    /** 读画像。库里没有这个用户时返回一份空画像，而不是 null——调用方不用到处判空。 */
    public UserProfile read(String userId) {
        return repository.find(userId).orElseGet(UserProfile::empty);
    }

    /** 最近的体重流水，新的在前。 */
    public List<UserProfile.WeightRecord> history(String userId) {
        return repository.findWeightHistory(userId);
    }

    /**
     * 合并写入，返回合并后的完整画像。
     *
     * <p>给对话用：只覆盖 {@code incoming} 里非空的那几个字段。
     *
     * @param incoming 这次要改的字段，没提到的传 null
     * @throws IllegalArgumentException 一个字段都没传。工具那条路径会先自己判掉，
     *                                  这里的异常是给「将来有人加了新调用点却忘了判」兜底的
     */
    public UserProfile update(String userId, UserProfile incoming) {
        if (incoming == null || incoming.isBlank()) {
            throw new IllegalArgumentException("没有传来任何可以保存的字段");
        }
        UserProfile existing = read(userId);
        return persist(userId, existing.merge(incoming), existing.weightKg());
    }

    /**
     * 整体替换，返回写入后的画像。
     *
     * <p>给设置页用：{@code incoming} 里 null 的字段就是「清空」，不是「没填」。
     */
    public UserProfile replace(String userId, UserProfile incoming) {
        UserProfile existing = read(userId);
        return persist(userId, incoming, existing.weightKg());
    }

    /**
     * 落库，两条路径共同的收尾。
     *
     * <p>时间戳在这里统一盖，不由调用方传：它是「这次什么时候写进去的」，
     * 该由服务端说了算。让调用方传的话，工具那条路径一定会有人传 {@code now()}、
     * 另一条忘了传 null，库里就会出现一行没有更新时间的画像。
     *
     * @param previousWeight 改动<b>之前</b>的体重，用来判断这次要不要记流水
     */
    private UserProfile persist(String userId, UserProfile profile, Double previousWeight) {
        LocalDateTime now = LocalDateTime.now();
        UserProfile stamped = new UserProfile(
                profile.age(), profile.heightCm(), profile.weightKg(), profile.goal(),
                profile.activityLevel(), profile.dietPreference(), profile.avoidFood(), now);

        repository.save(userId, stamped);

        if (weightChanged(previousWeight, stamped.weightKg())) {
            repository.appendWeight(userId, stamped.weightKg(), now);
            log.info("[Profile] userId={} 体重 {} kg → {} kg，已记入体重历史",
                    userId, previousWeight, stamped.weightKg());
        }
        log.info("[Profile] userId={} 画像已写入", userId);
        return stamped;
    }

    /**
     * 体重是不是真的变了。
     *
     * <p>判断的前提是「体重变了才记流水」：把已知的体重重复写一遍是常见操作
     * （模型尤其爱这么干，表单每次保存也会带上没改的体重），逐次照记的话，
     * 流水会被同一个数字淹没，趋势也就看不出来了。
     *
     * <p>{@code incoming} 为 null 是「现在没有体重」——那也算变化，
     * 但只在原来有值的时候才是（原来也没有就不必记一条空流水）。
     * 用 {@link Double#compare} 而不是 {@code !=}：这些值是装箱的 {@code Double}，
     * 拿 {@code !=} 比的是引用，同样的 70.0 在两个不同对象上会判成「变了」。
     */
    private static boolean weightChanged(Double previous, Double current) {
        if (current == null) {
            return false;
        }
        return previous == null || Double.compare(previous, current) != 0;
    }
}
