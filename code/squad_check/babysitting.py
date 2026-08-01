from PIL import Image, ImageDraw, ImageFont
import random

img = Image.new('RGB', (900, 600), (255, 250, 240))
draw = ImageDraw.Draw(img)

draw.rectangle([0, 480, 900, 600], fill=(140, 200, 100))

try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 18)
    sfont = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 11)
except:
    font = sfont = ImageFont.load_default()

draw.text((250, 20), "BABYSITTING DUTY", fill=(150, 90, 50), font=font)
draw.text((280, 48), "equations x wolf pups", fill=(160, 110, 70), font=sfont)

def draw_equation(draw, bx, by, c=(200,200,220), tired=False):
    draw.rectangle([bx, by, bx+20, by+18], fill=c, outline=(80,80,80))
    if tired:
        draw.line([(bx+5, by+6), (bx+8, by+6)], fill=(30,30,30), width=1)
        draw.line([(bx+12, by+6), (bx+15, by+6)], fill=(30,30,30), width=1)
        draw.ellipse([bx+20, by+2, bx+23, by+6], fill=(150,200,255))
    else:
        draw.ellipse([bx+5, by+5, bx+8, by+8], fill=(30,30,30))
        draw.ellipse([bx+12, by+5, bx+15, by+8], fill=(30,30,30))
        draw.arc([bx+7, by+9, bx+13, by+14], 0, 180, fill=(30,30,30))
    draw.rectangle([bx+4, by+18, bx+7, by+22], fill=c, outline=(80,80,80))
    draw.rectangle([bx+13, by+18, bx+16, by+22], fill=c, outline=(80,80,80))

def draw_wolfpup(draw, bx, by, chaos=False):
    c = (240, 220, 180)
    draw.polygon([(bx+1, by), (bx+4, by-6), (bx+7, by)], fill=(220, 200, 160))
    draw.polygon([(bx+9, by), (bx+12, by-6), (bx+15, by)], fill=(220, 200, 160))
    draw.ellipse([bx, by, bx+16, by+14], fill=c, outline=(160,140,100))
    if chaos:
        draw.ellipse([bx+3, by+4, bx+6, by+7], fill=(30,30,30))
        draw.ellipse([bx+10, by+4, bx+13, by+7], fill=(30,30,30))
        draw.ellipse([bx+5, by+9, bx+11, by+13], fill=(200,100,100))
    else:
        draw.ellipse([bx+4, by+5, bx+6, by+7], fill=(30,30,30))
        draw.ellipse([bx+10, by+5, bx+12, by+7], fill=(30,30,30))

ecolors = [(200,200,220), (180,210,230), (220,200,210), (210,220,190), (230,210,200)]

draw_equation(draw, 100, 400, ecolors[0], tired=True)
draw.line([(120, 410), (80, 380)], fill=(80,80,80), width=2)
draw_wolfpup(draw, 60, 370, chaos=True)
draw.line([(120, 410), (155, 380)], fill=(80,80,80), width=2)
draw_wolfpup(draw, 145, 370, chaos=True)

draw_equation(draw, 280, 420, ecolors[1], tired=True)
draw_wolfpup(draw, 340, 410, chaos=True)
draw_wolfpup(draw, 370, 400, chaos=True)
draw.line([(360, 415), (380, 415)], fill=(180,180,180), width=1)
draw.line([(360, 420), (385, 420)], fill=(180,180,180), width=1)

draw_equation(draw, 500, 430, ecolors[2], tired=True)
draw_wolfpup(draw, 502, 410, chaos=False)

draw_equation(draw, 680, 410, ecolors[3], tired=True)
draw_wolfpup(draw, 650, 400, chaos=True)
draw_wolfpup(draw, 660, 420, chaos=True)
draw_wolfpup(draw, 700, 395, chaos=True)
draw_wolfpup(draw, 710, 425, chaos=True)

for i in range(8):
    ex = random.randint(50, 820)
    ey = random.randint(200, 370)
    draw_equation(draw, ex, ey, ecolors[i % 5], tired=random.random() > 0.3)

for i in range(12):
    wx = random.randint(50, 830)
    wy = random.randint(180, 380)
    draw_wolfpup(draw, wx, wy, chaos=random.random() > 0.4)

draw.ellipse([90, 340, 200, 370], fill=(255,255,255), outline=(180,180,180))
draw.text((100, 347), "help...", fill=(150,80,80), font=sfont)

draw.text((250, 560), "left hand 1, right hand 1, back 1, no hands left", fill=(120, 90, 60), font=sfont)
draw.text((330, 578), "proud nanny: equations x1~x40", fill=(100, 80, 50), font=sfont)

img.save('babysitting.png')
print("done - babysitting.png")
