import glob
from PIL import Image
im=Image.open('icon1.png').convert('RGBA')
for p in glob.glob('android/app/src/main/res/mipmap-*/ic_launcher*.png'):
    s=Image.open(p).size[0]
    im.resize((s,s)).save(p)
