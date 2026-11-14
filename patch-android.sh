M=android/app/src/main/AndroidManifest.xml
for P in CAMERA RECORD_AUDIO MODIFY_AUDIO_SETTINGS POST_NOTIFICATIONS; do
  grep -q "android.permission.$P\"" $M || sed -i "s#</manifest>#    <uses-permission android:name=\"android.permission.$P\" />\n</manifest>#" $M
done
grep -q "hardware.camera\"" $M || sed -i 's#</manifest>#    <uses-feature android:name="android.hardware.camera" android:required="false" />\n</manifest>#' $M
S=android/app/src/main/res/values
sed -i 's#<string name="app_name">[^<]*#<string name="app_name">سبق#; s#<string name="title_activity_main">[^<]*#<string name="title_activity_main">سبق#' $S/strings.xml
sed -i 's#<item name="windowActionBar">false</item>#&<item name="android:windowLightStatusBar">false</item>#' $S/styles.xml
python3 patch_cfg.py
