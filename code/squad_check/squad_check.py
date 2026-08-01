from PIL import Image, ImageDraw, ImageFont
import random

img = Image.new('L', (600, 500), 0)
draw = ImageDraw.Draw(img)

for y in range(60, 460):
    spread = int((y - 60) * 0.7)
    cx = 300
    for x in range(cx - spread, cx + spread):
        if 0 <= x < 600:
            noise = random.randint(20, 60)
            img.putpixel((x, y), noise)

positions = []
for i in range(40):
    while True:
        bx = random.randint(160, 440)
        by = random.randint(120, 400)
        overlap = False
        for px, py in positions:
            if abs(bx - px) < 22 and abs(by - py) < 22:
                overlap = True
                break
        if not overlap:
            positions.append((bx, by))
            break

for bx, by in positions:
    c = random.randint(180, 240)
    draw.rectangle([bx, by, bx+10, by+8], fill=c)
    draw.point((bx+3, by+2), fill=30)
    draw.point((bx+7, by+2), fill=30)
    draw.rectangle([bx+2, by+8, bx+3, by+10], fill=c)
    draw.rectangle([bx+7, by+8, bx+8, by+10], fill=c)

try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 14)
except:
    font = ImageFont.load_default()
draw.text((430, 30), "SQUAD CHECK", fill=220, font=font)

img.save('squad_check.png')
print("done - squad_check.png")
