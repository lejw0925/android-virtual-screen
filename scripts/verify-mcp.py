#!/usr/bin/env python3
"""Verify MCP transport and control against the project's virtual-display fixture.

Run after starting the debug app's fixture and MCP, with an ADB port forward.
Never prints credentials and never operates an unrelated app.
"""
import argparse
import concurrent.futures
import json
from pathlib import Path
import threading
import time
import urllib.error
import urllib.request

FIXTURE = 'io.github.lejw0925.virtualscreen.fixture'
VERSION = '2025-11-25'

class Client:
    def __init__(self, url, token, name):
        self.url, self.token, self.name = url, token, name
        self.session, self.counter = None, 0
        self.lock = threading.Lock()
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        response, headers, status = self.request('initialize', {
            'protocolVersion': VERSION, 'capabilities': {},
            'clientInfo': {'name': name, 'version': '1'},
        })
        assert status == 200 and response['result']['protocolVersion'] == VERSION
        self.session = headers['Mcp-Session-Id']
        assert self.request('notifications/initialized', notification=True)[2] == 202

    def next_id(self):
        with self.lock:
            self.counter += 1
            return self.name + '-' + str(self.counter)

    def request(self, method=None, params=None, notification=False, request_id=None, http_method='POST', token=None):
        headers = {'Authorization': 'Bearer ' + (self.token if token is None else token),
                   'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
        if self.session:
            headers.update({'Mcp-Session-Id': self.session, 'MCP-Protocol-Version': VERSION})
        payload = {'jsonrpc': '2.0', 'method': method}
        if not notification:
            payload['id'] = request_id if request_id is not None else self.next_id()
        if params is not None:
            payload['params'] = params
        data = json.dumps(payload, ensure_ascii=False).encode() if http_method == 'POST' else None
        request = urllib.request.Request(self.url, data=data, headers=headers, method=http_method)
        try:
            response = self.http.open(request, timeout=40)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read()
            return json.loads(body) if body else None, response.headers, response.status

    def tool(self, name, purpose, **arguments):
        arguments['purpose'] = purpose
        response, _, status = self.request('tools/call', {'name': name, 'arguments': arguments})
        assert status == 200 and 'result' in response, 'MCP call failed: ' + name
        return response['result']

    def close(self):
        if self.session:
            assert self.request(http_method='DELETE')[2] in (204, 404)
            self.session = None

def data(result, ok=True):
    value = result['structuredContent']
    assert value['ok'] == ok, 'Unexpected operation status: ' + str(value.get('code'))
    return value

def observe(client, lease):
    # A resized Android window can briefly expose its previous accessibility bounds.
    # Only observations are retried; no accepted input is replayed.
    deadline = time.monotonic() + 8
    while True:
        result = client.tool('observe', '观察测试应用，核对操作结果', lease_token=lease, mode='both')
        value = data(result)
        nodes = value.get('nodes') or []
        counter = next((n for n in nodes if n.get('clickable') and 'tap counter' in (n.get('text') or '').lower()), None)
        bounds = counter.get('bounds') if counter else None
        geometry_ready = not bounds or (0 <= (bounds[0]+bounds[2])//2 < value['width'] and
                                        0 <= (bounds[1]+bounds[3])//2 < value['height'])
        if value.get('package') == FIXTURE and nodes and geometry_ready:
            break
        if time.monotonic() >= deadline:
            raise AssertionError('Disposable fixture nodes or resized layout did not become ready: ' + str(value.get('nodes_error')))
        time.sleep(.25)
    assert any(c.get('type') == 'image' and c.get('mimeType') == 'image/jpeg' for c in result['content'])
    return value

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:18732/mcp')
    parser.add_argument('--token-file', type=Path, required=True, help='Private token or debug JSON file')
    parser.add_argument('--manual-marker', type=Path, help='Write phone tap coordinates and await a .done marker')
    args = parser.parse_args()
    raw = args.token_file.read_text().strip()
    token = json.loads(raw)['token'] if raw.startswith('{') else raw
    first = Client(args.url, token, 'verification-controller')
    second = Client(args.url, token, 'verification-observer')
    lease = None
    try:
        assert first.request('ping', token='invalid')[2] == 401
        assert first.request(http_method='GET')[2] == 405
        tools = first.request('tools/list')[0]['result']['tools']
        expected_tools = {
            'acquire_control', 'release_control', 'request_control', 'get_status', 'get_request_receipt',
            'create_screen', 'configure_screen', 'close_screen', 'list_apps', 'launch_app', 'observe',
            'tap_at', 'long_press_at', 'swipe_path', 'scroll_at', 'input_text', 'node_action', 'press_key', 'wait',
        }
        assert {t['name'] for t in tools} == expected_tools and len(tools) == len(expected_tools)
        assert all('purpose' in t['inputSchema']['required'] for t in tools)
        missing = first.request('tools/call', {'name': 'get_status', 'arguments': {}})[0]['result']
        assert data(missing, False)['code'] == 'PURPOSE_REQUIRED'
        lease = data(first.tool('acquire_control', '申请测试应用的独占控制权'))['lease_token']
        busy = data(second.tool('acquire_control', '检查第二客户端的控制竞争'), False)
        assert busy['code'] == 'CONTROL_BUSY'
        status = data(second.tool('get_status', '查看当前控制方与设备能力'))
        assert status['controller'] == first.session
        print(f'Authentication, {len(tools)} tools, required purpose and exclusive lease verified.', flush=True)

        frame = observe(first, lease)
        button = next(n for n in frame['nodes'] if n.get('clickable') and 'tap counter' in (n.get('text') or '').lower())
        initial_count = int(button['text'].split(':')[-1])
        x1,y1,x2,y2 = button['bounds']
        x,y = (x1+x2)//2,(y1+y2)//2
        assert 0 <= x < frame['width'] and 0 <= y < frame['height'], 'Wait for the fixture layout to settle before coordinate input'
        pointer = {'name': 'tap_at', 'arguments': {'x': x, 'y': y,
                   'lease_token': lease, 'observation_id': frame['observation_id'], 'purpose': '点击测试计数器，验证输入一次执行'}}
        request_id = first.next_id()
        result = first.request('tools/call', pointer, request_id=request_id)[0]
        data(result['result'])
        repeated = first.request('tools/call', pointer, request_id=request_id)[0]
        assert repeated['error']['code'] == -32001
        consumed = first.tool('tap_at', '检查已消费的观察会被拒绝', x=x, y=y,
                              lease_token=lease, observation_id=frame['observation_id'])
        assert data(consumed, False)['code'] == 'STALE_OBSERVATION'
        data(first.tool('wait', '等待计数器状态确认，避免重复输入', condition='node_text', text='tap counter: '+str(initial_count+1), timeout_ms=5000, lease_token=lease))
        frame = observe(first, lease)
        assert any(('tap counter: '+str(initial_count+1)) == (n.get('text') or '').lower() for n in frame['nodes'])
        print('Real virtual-display input, screenshot and duplicate/stale rejection verified.', flush=True)

        if args.manual_marker:
            marker = args.manual_marker
            done = Path(str(marker)+'.done')
            done.unlink(missing_ok=True)
            marker.write_text(json.dumps({'virtual_x': x, 'virtual_y': y}))
            print('Manual takeover ready; waiting for the phone fixture tap.', flush=True)
            deadline = time.monotonic()+150
            while not done.exists() and time.monotonic()<deadline:
                time.sleep(.2)
            assert done.exists(), 'Manual takeover was not performed'
            time.sleep(1)
            revoked = first.tool('observe', '检查本地接管后的旧租约', lease_token=lease)
            assert data(revoked, False)['code'] == 'LEASE_REQUIRED'
            lease = data(first.tool('acquire_control', '本地手势结束后重新取得控制权'))['lease_token']
            stale = first.tool('tap_at', '检查接管前的观察失效', x=x, y=y,
                               lease_token=lease, observation_id=frame['observation_id'])
            assert data(stale, False)['code'] == 'STALE_OBSERVATION'
            frame = observe(first, lease)
            assert any(('tap counter: '+str(initial_count+2)) == (n.get('text') or '').lower() for n in frame['nodes'])
            marker.unlink(missing_ok=True); done.unlink(missing_ok=True)
            print('Native local gesture, automatic remote handoff and observation fencing verified.', flush=True)

        editor = next(n for n in frame['nodes'] if n.get('editable'))
        data(first.tool('node_action', '聚焦测试编辑框，准备验证中文输入', action='focus', node_id=editor['id'],
                        lease_token=lease, observation_id=frame['observation_id']))
        frame = observe(first, lease)
        data(first.tool('input_text', '向测试应用输入中文与繁体字', text='中文输入測試', replace=True,
                        lease_token=lease, observation_id=frame['observation_id']))
        data(first.tool('wait', '等待中文字输入效果确认', condition='node_text', text='中文输入測試', timeout_ms=5000, lease_token=lease))
        frame = observe(first, lease)
        assert any(n.get('text') == '中文输入測試' for n in frame['nodes'])
        scroll = next(n for n in frame['nodes'] if n.get('scrollable'))
        data(first.tool('node_action', '向下滚动测试列表', action='scroll_forward', node_id=scroll['id'],
                        lease_token=lease, observation_id=frame['observation_id']))
        print('Node focus, Unicode text and display-scoped scroll verified.', flush=True)

        wait_id = first.next_id()
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            waiting = executor.submit(first.request, 'tools/call', {'name': 'wait', 'arguments': {
                'condition': 'delay', 'timeout_ms': 30000, 'lease_token': lease,
                'purpose': '等待测试界面并验证请求取消'}}, request_id=wait_id)
            time.sleep(.5)
            first.request('notifications/cancelled', {'requestId': wait_id, 'reason': 'verification'}, notification=True)
            cancelled = waiting.result(timeout=10)[0]
            assert cancelled['error']['code'] == -32800
        observe(first, lease)
        data(first.tool('release_control', '测试完成，释放控制权', lease_token=lease))
        lease = None
        print('Cancellation, re-observation, lease release and session close verified.', flush=True)
    finally:
        if lease:
            first.tool('release_control', '结束验证并释放控制权', lease_token=lease)
        first.close(); second.close()

if __name__ == '__main__':
    main()
