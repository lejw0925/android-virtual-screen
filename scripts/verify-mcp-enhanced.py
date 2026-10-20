#!/usr/bin/env python3
"""Exercise 0.0.1 MCP observations, selectors and workflows on the disposable fixture.

Start the fixture in the virtual display, enable MCP, then use the same private
--token-file and ADB forward as verify-mcp.py. No credential is logged.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import statistics
import time

spec = importlib.util.spec_from_file_location('mcp_verify', Path(__file__).with_name('verify-mcp.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:18732/mcp')
    parser.add_argument('--token-file', type=Path, required=True)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--samples', type=int, default=10)
    args = parser.parse_args()
    assert 1 <= args.samples <= 50
    raw = args.token_file.read_text().strip()
    token = json.loads(raw)['token'] if raw.startswith('{') else raw
    client = base.Client(args.url, token, 'enhanced-workflow')
    lease = base.data(client.tool('acquire_control', '验证高效观察和准确操作'))['lease_token']
    records = {}
    try:
        def call(name, **values):
            return client.tool(name, '在独立测试应用中验证 ' + name, lease_token=lease, **values)
        def observe(**values):
            result = call('observe', **values)
            state = base.data(result)
            assert state.get('package') == base.FIXTURE, 'Only the disposable fixture can be tested'
            assert not state['truncated']
            return state, result
        # Use a known geometry and a fresh fixture task. Reconfiguration replaces
        # the display; accessibility windows can lag until the task settles.
        base.data(call('configure_screen', width=720, height=1600, density=320, observe_after='none'))
        base.data(call('launch_app', component=base.FIXTURE+'/.FixtureActivity', observe_after='none'))
        base.observe(client, lease)
        for mode, fmt in [('tree', 'full'), ('tree', 'compact'), ('both', 'compact')]:
            rows = []
            for _ in range(args.samples):
                start = time.perf_counter()
                state, result = observe(mode=mode, format=fmt)
                rows.append({'ms': round((time.perf_counter()-start)*1000, 2),
                             'bytes': len(json.dumps(result).encode()), 'nodes': state['returned_node_count']})
                if mode == 'tree':
                    assert not any(c['type'] == 'image' for c in result['content'])
            records[mode+'_'+fmt] = {'samples': rows, 'median_ms': statistics.median(r['ms'] for r in rows),
                                    'mean_bytes': statistics.mean(r['bytes'] for r in rows)}
        assert records['tree_compact']['mean_bytes'] < records['tree_full']['mean_bytes'], \
            'Compact tree should serialize smaller than full for the same fixture hierarchy'
        state, _ = observe(mode='tree')
        original = state['observation_id']
        button = next(n for n in state['nodes'] if 'tap counter' in (n.get('text') or '').lower())
        count = int(button['text'].split(':')[-1])
        ambiguous = call('node_action', action='click', selector={'class': 'android.widget.TextView'}, observation_id=original)
        assert base.data(ambiguous, False)['code'] == 'AMBIGUOUS_NODE'
        invalid = call('press_key', key_code=3, observation_id=original)
        assert base.data(invalid, False)['code'] == 'INVALID_ARGUMENT'
        invalid = call('long_press_at', x=5, y=5, duration_ms=100, observation_id=original)
        assert base.data(invalid, False)['code'] == 'INVALID_ARGUMENT'
        clicked = base.data(call('node_action', action='click', selector={'resource_id': base.FIXTURE+':id/test_counter'},
                                 observation_id=original, observe_after='tree'))
        assert clicked['execution_status'] == 'accepted' and clicked['effect_status'] == 'unconfirmed'
        assert clicked['observation_status'] == 'available' and clicked['observation_id'] != original
        receipt = base.data(client.tool('get_request_receipt', '查询原请求回执，确认无需重发输入', request_id=clicked['request_id']))
        assert receipt['receipt_found'] and receipt['receipt']['execution_status'] == 'accepted'
        other = base.Client(args.url, token, 'receipt-isolation-check')
        try:
            assert not base.data(other.tool('get_request_receipt', '确认回执只属于原会话', request_id=clicked['request_id']))['receipt_found']
        finally:
            other.close()
        stale = call('node_action', action='click', node_id=button['id'], observation_id=original)
        assert base.data(stale, False)['code'] == 'STALE_OBSERVATION'
        updated = base.data(call('wait', condition='node_text', text='tap counter: '+str(count+1), timeout_ms=5000))
        assert updated['condition_matched']
        editor = next(n for n in updated['nodes'] if n.get('editable'))
        text = 'Agent 中文測試 👋 0.0.1'
        base.data(call('node_action', action='set_text', node_id=editor['id'], value=text, observation_id=updated['observation_id'], observe_after='tree'))
        typed = base.data(call('wait', condition='node_text', text=text, timeout_ms=5000))
        assert any(n.get('text') == text for n in typed['nodes'])
        base.data(call('node_action', action='set_text', selector={'resource_id': base.FIXTURE+':id/test_editor'}, value='', observation_id=typed['observation_id']))
        base.data(call('wait', condition='node_present', selector={'resource_id': base.FIXTURE+':id/test_editor'}, timeout_ms=2000))
        absent = base.data(call('wait', condition='node_absent', selector={'text': 'definitely missing fixture node'}, timeout_ms=2000))
        assert absent['condition_matched']
        small = call('observe', mode='both', max_image_width=360)
        image = base.data(small)
        assert image['image_width'] == 360 and image['width'] == 720
        assert image['image_to_display_scale'][0] == 2 and image['coordinate_space'] == 'display'
        assert any(c['type'] == 'image' for c in small['content'])
        stable = base.data(call('wait', condition='ui_stable', timeout_ms=5000))
        assert stable['condition_matched']
        apps = base.data(call('list_apps', package=base.FIXTURE))['applications']
        assert apps and all(a['package'] == base.FIXTURE for a in apps)
        records['workflow'] = {'unique_selector': True, 'ambiguous_rejected': True, 'unsupported_key_rejected': True,
                               'short_long_press_rejected': True, 'after_observation': True, 'stale_rejected': True,
                               'unicode': True, 'clear_text': True, 'node_waits': True, 'image_mapping': True,
                               'ui_stable': True, 'application_filter': True, 'counter_increment': 1, 'receipt_recovery': True, 'receipt_isolation': True}
        args.results.write_text(json.dumps(records, indent=2))
        print(json.dumps({k: {a: v[a] for a in ('median_ms', 'mean_bytes')} if k != 'workflow' else v for k, v in records.items()}, indent=2))
    finally:
        client.tool('release_control', '工作流验证结束释放控制权', lease_token=lease)
        client.close()

if __name__ == '__main__':
    main()
