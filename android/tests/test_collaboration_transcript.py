"""User-facing transcripts survive throttling, concurrent workers and reloads."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from collaboration_transcript import AttemptTranscript, conversation


def event(method, **params):
    return {'method':method,'params':params,'receivedAt':10}


class TranscriptTests(unittest.TestCase):
    def test_stream_sequence_survives_reload_and_deduplicates_events(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'transcript.json';t=AttemptTranscript(path)
            first=dict(event('item/agentMessage/delta',itemId='answer',delta='hel'),attemptSequence=1)
            second=dict(event('item/agentMessage/delta',itemId='answer',delta='lo'),attemptSequence=2)
            t.apply(first);t.apply(second);t.apply(second);t.apply(first);t.flush(force=True)
            saved=AttemptTranscript(path).snapshot()
            self.assertEqual(saved['revision'],2);self.assertEqual(saved['items'][0]['text'],'hello')
            t.finish({'status':'succeeded'});self.assertGreater(AttemptTranscript(path).snapshot()['revision'],2)
    def test_fast_deltas_reasoning_commands_and_reload(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'transcript.json';t=AttemptTranscript(path)
            t.apply(event('item/started',item={'id':'internal','type':'userMessage','content':[{'text':'private generated worker prompt'}]}))
            for char in 'streamed reply':t.apply(event('item/agentMessage/delta',itemId='answer',delta=char))
            for char in 'summary':t.apply(event('item/reasoning/summaryTextDelta',itemId='reason',summaryIndex=0,delta=char))
            t.apply(event('item/started',item={'id':'cmd','type':'commandExecution','command':'pwd'}))
            for part in ('/project','\n'):t.apply(event('item/commandExecution/outputDelta',itemId='cmd',delta=part))
            t.finish({'status':'interrupted'})
            values={i['id']:i for i in AttemptTranscript(path).snapshot()['items']}
            self.assertEqual(values['answer']['text'],'streamed reply')
            self.assertEqual(values['reason']['summary'],['summary'])
            self.assertEqual(values['cmd']['aggregatedOutput'],'/project\n')
            self.assertNotIn('internal',values)
            self.assertEqual(AttemptTranscript(path).snapshot()['status'],'interrupted')
            self.assertEqual(path.stat().st_mode&0o777,0o600)

    def document(self):
        return {'participants':[{'id':'p','engine':'deepseek','name':'Changed','model':'new'}],
            'tasks':[{'id':'t','title':'Task','participantId':'p','attempts':[
                {'id':'a','status':'succeeded','startedAt':1,'finishedAt':3,'result':'first','config':{'id':'old','engine':'codex','name':'Coder','model':'model-one'}},
                {'id':'b','status':'running','startedAt':4,'config':{'id':'p','engine':'claude','name':'Reviewer','model':'model-two'}}]}]}

    def test_same_item_ids_are_isolated_sources_frozen_and_pagination_stable(self):
        with tempfile.TemporaryDirectory() as folder:
            first=AttemptTranscript(Path(folder)/'attempts/a/transcript.json');second=AttemptTranscript()
            for t,at,text in ((first,2,'first'),(second,5,'second')):
                t.apply(dict(event('item/started',item={'id':'same','type':'agentMessage','text':text}),receivedAt=at))
            first.finish({'status':'succeeded'})
            d=self.document();live={'b':second}
            page=conversation(d,folder,live,{'limit':1})
            self.assertEqual(page['entries'][0]['id'],'b:same');self.assertEqual(page['entries'][0]['model'],'model-two')
            earlier=conversation(d,folder,live,{'before':page['before'],'limit':1})
            self.assertEqual(earlier['entries'][0]['id'],'a:same');self.assertEqual(earlier['entries'][0]['model'],'model-one')
            self.assertEqual(earlier['entries'][0]['engine'],'codex')
            self.assertFalse(earlier['hasMore'])
            self.assertEqual(len(conversation(d,folder,live,{'participantId':'old'})['entries']),1)
            self.assertEqual(len(conversation(d,folder,live,{'taskId':'absent'})['entries']),0)

    def test_legacy_partial_history_and_corrupt_history_keep_final_result(self):
        with tempfile.TemporaryDirectory() as folder:
            d=self.document();d['tasks'][0]['attempts']=d['tasks'][0]['attempts'][:1]
            d['tasks'][0]['attempts'][0]['events']=[{'method':'item/agentMessage/delta','params':{'itemId':'answer','delta':'partial'}}]
            first=conversation(d,folder,{},{});second=conversation(d,folder,{},{})
            self.assertTrue(first['historyPartial']);self.assertEqual(first,second)
            self.assertEqual([e['item']['text'] for e in first['entries']],['partial','first'])
            path=Path(folder)/'attempts/a/transcript.json';path.parent.mkdir(parents=True);path.write_text('{broken')
            recovered=conversation(d,folder,{},{})
            self.assertTrue(recovered['historyPartial']);self.assertTrue(any(e['kind']=='error' for e in recovered['entries']))
            self.assertTrue(any(e.get('item',{}).get('text')=='first' for e in recovered['entries']))

    def test_completion_updates_existing_item_without_reordering_or_duplication(self):
        t=AttemptTranscript();t.apply(event('item/agentMessage/delta',itemId='answer',delta='hel'))
        t.apply(dict(event('item/completed',item={'id':'answer','type':'agentMessage','text':'hello','startedAt':'native timestamp'}),receivedAt=20))
        t.finish({'status':'succeeded','turn':{'items':[{'id':'answer','type':'agentMessage','text':'hello'}]}})
        items=t.snapshot()['items'];self.assertEqual(len(items),1);self.assertEqual(items[0]['startedAt'],10);self.assertEqual(items[0]['text'],'hello')
