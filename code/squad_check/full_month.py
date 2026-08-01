from PIL import Image, ImageDraw, ImageFont
import random

img = Image.new('RGB', (800, 600), (255, 245, 230))
draw = ImageDraw.Draw(img)

draw.ellipse([300, 20, 500, 220], fill=(255, 240, 150), outline=(255, 220, 100))

try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 20)
    sfont = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 12)
except:
    font = ImageFont.load_default()
    sfont = font

draw.text((280, 240), "FULL MONTH PARTY", fill=(180, 100, 60), font=font)
draw.text((310, 270), "x1 ~ x40", fill=(150, 80, 40), font=sfont)

colors = [(200,200,220), (180,210,230), (220,200,210), (210,220,190), (230,210,200)]

positions = []
for i in range(40):
    while True:
        bx = random.randint(80, 700)
        by = random.randint(300, 550)
        overlap = False
        for px, py in positions:
            if abs(bx - px) < 28 and abs(by - py) < 28:
                overlap = True
                break
        if not overlap:
            positions.append((bx, by))
            break

for idx, (bx, by) in enumerate(positions):
    c = colors[idx % len(colors)]
    draw.rectangle([bx, by, bx+16, by+14], fill=c, outline=(100,100,100))
    draw.ellipse([bx+4, by+4, bx+6, by+7], fill=(30,30,30))
    draw.ellipse([bx+10, by+4, bx+12, by+7], fill=(30,30,30))
    draw.arc([bx+5, by+7, bx+11, by+12], 0, 180, fill=(30,30,30))
    draw.rectangle([bx+3, by+14, bx+5, by+17], fill=c, outline=(100,100,100))
    draw.rectangle([bx+11, by+14, bx+13, by+17], fill=c, outline=(100,100,100))
    draw.polygon([(bx+4, by), (bx+8, by-8), (bx+12, by)], fill=(255, random.randint(100,200), random.randint(80,150)))

draw.text((250, 565), "proud parents: chen x xiaochen", fill=(160, 120, 80), font=sfont)

img.save('full_month.png')
print("done - full_month.png")
