"""릴리스마다 app-meta.json 을 만든다.
app/native-level 이 올라가면 앱(APK)을 새로 깔아야 하는 업데이트, 아니면 편집기 HTML만 바뀌어 앱이 알아서 받는다."""
import json, os, shutil, sys
run = int(sys.argv[1])
rel = json.load(open('release.json', encoding='utf-8'))
notes = (rel.get('notes') or '').strip()
if not notes:
    sys.exit('release.json 의 notes 를 확인하세요')
native = int(open('app/native-level').read().strip())
prev = json.load(open('prev/app-meta.json', encoding='utf-8')) if os.path.exists('prev/app-meta.json') else {}
meta = {'content': run, 'native': native, 'notes': notes, 'nativeNotes': prev.get('nativeNotes', '')}
if native > prev.get('native', 0):
    meta['nativeNotes'] = notes
os.makedirs('out', exist_ok=True)
json.dump(meta, open('out/app-meta.json', 'w', encoding='utf-8'), ensure_ascii=False)
shutil.copy('app/src/main/assets/index.html', 'out/editor.html')
open('out/notes.txt', 'w', encoding='utf-8').write(notes + '\n\n휴대폰에서 아래 Assets의 apk 파일을 눌러 받아 설치하세요.\n')
print(json.dumps(meta, ensure_ascii=False))
