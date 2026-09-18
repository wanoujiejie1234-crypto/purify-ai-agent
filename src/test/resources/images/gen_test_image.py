"""生成测试用图片。

说明：测试图片本可以直接提交一张二进制文件，但二进制在 code review 里没法看，
所以把「画什么」用脚本记录下来，需要时重跑即可（需要 Pillow）：

    python src/test/resources/images/gen_test_image.py

当前画的是一个「白底 + 红色圆形 + 绿色叶子」的苹果轮廓，
用来验证模型确实读到了图片内容（回答里应该出现「红」）。
"""

from PIL import Image, ImageDraw

WIDTH = HEIGHT = 256

image = Image.new("RGB", (WIDTH, HEIGHT), (255, 255, 255))
draw = ImageDraw.Draw(image)

# 苹果主体：一个大红圆
draw.ellipse([48, 72, 208, 232], fill=(214, 40, 40))
# 叶子：绿色小圆
draw.ellipse([152, 28, 186, 62], fill=(46, 160, 67))
# 果柄：棕色短线
draw.rectangle([124, 40, 132, 76], fill=(121, 85, 72))

image.save("apple.png")
print("apple.png generated:", image.size)
