from PIL import Image, ImageDraw, ImageFont
import random

img = Image.new('RGB', (900, 600), (240, 248, 255))
draw = ImageDraw.Draw(img)

draw.rectangle([300, 30, 600, 160], fill=(200, 180, 160), outline=(120, 100, 80))
draw.rectangle([410, 60, 490, 140], fill=(180, 220, 255), outline=(100, 100, 100))
draw.rectangle([430, 110, 470, 160], fill=(139, 90, 43), outline=(100, 70, 30))
draw.polygon([(280, 30), (450, -20), (620, 30)], fill=(180, 60, 60))

try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 16)
    sfont = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 11)
    tfont = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 13)
except:
    font = sfont = tfont = ImageFont.load_default()

draw.text((350, 70), "EQUATION", fill=(80, 60, 40), font=tfont)
draw.text((340, 88), "ELEMENTARY", fill=(80, 60, 40), font=tfont)

draw.rectangle([0, 500, 900, 600], fill=(120, 180, 80))

colors = [(200,200,220), (180,210,230), (220,200,210), (210,220,190), (230,210,200)]

for row in range(4):
    for col in range(10):
        idx = row * 10 + col
        bx = 100 + col * 75
        by = 200 + row * 75
        c = colors[idx % len(colors)]
        draw.rectangle([bx, by, bx+18, by+16], fill=c, outline=(80,80,80))
        draw.ellipse([bx+5, by+5, bx+7, by+8], fill=(30,30,30))
        draw.ellipse([bx+11, by+5, bx+13, by+8], fill=(30,30,30))
        draw.arc([bx+6, by+8, bx+12, by+13], 0, 180, fill=(30,30,30))
        draw.rectangle([bx+4, by+16, bx+6, by+20], fill=c, outline=(80,80,80))
        draw.rectangle([bx+12, by+16, bx+14, by+20], fill=c, outline=(80,80,80))
        draw.rectangle([bx+18, by+3, bx+22, by+12], fill=(random.randint(150,255), random.randint(80,180), random.randint(80,150)))

draw.text((300, 550), "Class of x1~x40 | First Day!", fill=(60, 80, 40), font=font)
draw.text((330, 575), "proud parents: chen x xiaochen", fill=(80, 100, 60), font=sfont)

img.save('first_day_school.png')
print("done - first_day_school.png")
