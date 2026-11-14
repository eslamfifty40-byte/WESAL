import re
p='android/app/src/main/AndroidManifest.xml'
s=open(p).read()
perms=['CAMERA','RECORD_AUDIO','MODIFY_AUDIO_SETTINGS','READ_MEDIA_IMAGES','READ_EXTERNAL_STORAGE','INTERNET']
add=''.join('    <uses-permission android:name="android.permission.%s" />\n'%x for x in perms)
add+='    <uses-feature android:name="android.hardware.camera" android:required="false" />\n'
s=s.replace('</manifest>',add+'</manifest>')
open(p,'w').write(s)
