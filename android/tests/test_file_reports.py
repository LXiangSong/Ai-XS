"""File summaries must reflect real writes, not tool labels or model claims."""
import copy
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
from file_reports import TurnFileReports, inventory
from collaboration_transcript import AttemptTranscript
from conversation_usage import execution_stats

class FileReportsTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
  self.reports=TurnFileReports();self.reports.bind({'id':'thread','cwd':str(self.root)})
 def tearDown(self):self.tmp.cleanup()
 def event(self,method,turn='turn'):
  value={'method':method,'params':{'threadId':'thread','turn':{'id':turn,'items':[]}}}
  self.reports.observe(value);return value
 def test_real_files_modified_generated_deleted_and_unchanged_content(self):
  for name in ('edit.txt','delete.txt','same.txt'):(self.root/name).write_text('old')
  self.event('turn/started')
  (self.root/'edit.txt').write_text('new');(self.root/'delete.txt').unlink();(self.root/'same.txt').write_text('old')
  (self.root/'dist').mkdir();(self.root/'dist/output.apk').write_bytes(b'\x00\x01\x02')
  final=self.event('turn/completed');report=final['params']['turn']['items'][-1]
  self.assertEqual({c['path']:c['kind'] for c in report['changes']},{'edit.txt':'modified','delete.txt':'deleted','dist/output.apk':'created'})
  self.assertEqual(report['counts'],{'created':1,'modified':1,'deleted':1});self.assertFalse(report['partial'])
  self.assertEqual(execution_stats([report],'completed')['toolCalls'],0)
  transcript=AttemptTranscript();transcript.apply(final)
  self.assertEqual(transcript.snapshot()['items'][-1]['changes'],report['changes'])
 def test_text_changes_carry_line_counts_and_a_bounded_unified_diff(self):
  (self.root/'edit.txt').write_text('old one\nold two\n')
  self.event('turn/started')
  (self.root/'edit.txt').write_text('old one\nnew two\nnew three\n')
  (self.root/'new.txt').write_text('alpha\nbeta\n')
  report=self.event('turn/completed')['params']['turn']['items'][0]
  changes={c['path']:c for c in report['changes']}
  self.assertEqual((changes['edit.txt']['added'],changes['edit.txt']['removed']),(2,1))
  self.assertIn('+new two',changes['edit.txt']['diff']);self.assertIn('-old two',changes['edit.txt']['diff'])
  self.assertEqual((changes['new.txt']['added'],changes['new.txt']['removed']),(2,0))
  self.assertEqual((report['added'],report['removed']),(4,1))
 def test_report_precedes_the_final_agent_message(self):
  self.event('turn/started');(self.root/'a.txt').write_text('new')
  final={'method':'turn/completed','params':{'threadId':'thread','turn':{'id':'turn','items':[{'id':'final','type':'agentMessage','text':'done'}]}}}
  self.reports.observe(final)
  items=final['params']['turn']['items']
  self.assertEqual(items[0]['type'],'fileReport');self.assertEqual(items[1]['id'],'final')
 def test_symlink_targets_and_dependencies_are_not_scanned_and_scope_is_explicit(self):
  with tempfile.TemporaryDirectory() as other:
   external=Path(other)/'private.txt';external.write_text('old')
   (self.root/'linked').symlink_to(other,target_is_directory=True)
   (self.root/'node_modules').mkdir();(self.root/'node_modules/dependency').write_text('old')
   self.event('turn/started');external.write_text('new');(self.root/'node_modules/dependency').write_text('new')
   report=self.event('turn/completed')['params']['turn']['items'][0]
   self.assertEqual(report['changes'],[]);self.assertIn('node_modules',report['excluded']);self.assertEqual(report['root'],str(self.root))
 def test_incomplete_inventory_never_claims_false_creation_or_deletion(self):
  entry=((1,2,3,4,5),'hash')
  with patch('file_reports.inventory',side_effect=[({'old':entry},False,[]),({'new':entry},False,[])]):
   self.event('turn/started');report=self.event('turn/completed')['params']['turn']['items'][0]
  self.assertEqual(report['changes'],[]);self.assertTrue(report['partial']);self.assertIn('不完整',report['text'])
 def test_interrupted_turn_still_reports_partial_work_and_duplicate_end_is_safe(self):
  self.event('turn/started');(self.root/'partial.txt').write_text('written before stop')
  event={'method':'turn/completed','params':{'threadId':'thread','turn':{'id':'turn','status':'interrupted','items':[]}}}
  self.reports.observe(event);self.reports.observe(event)
  self.assertEqual(len(event['params']['turn']['items']),1)
  self.assertEqual(event['params']['turn']['items'][0]['changes'][0]['path'],'partial.txt')
 def test_runtime_store_is_excluded_but_build_output_is_included(self):
  runtime=self.root/'.state';runtime.mkdir();self.reports=TurnFileReports((runtime,));self.reports.bind({'id':'thread','cwd':str(self.root)})
  self.event('turn/started');(runtime/'trace.json').write_text('internal');(self.root/'report.pdf').write_bytes(b'PDF')
  report=self.event('turn/completed')['params']['turn']['items'][0]
  self.assertEqual([c['path'] for c in report['changes']],['report.pdf'])
