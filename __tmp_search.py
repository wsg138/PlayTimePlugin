import sys& echo files=[str(p) for p in root.rglob('*.java') if 'getDataSource' in p.read_text()]& echo print('\n'.join(files)) ) 
