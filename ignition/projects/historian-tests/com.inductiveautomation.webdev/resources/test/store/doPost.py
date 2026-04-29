def doPost(request, session):
	try:
		import traceback

		body = request['data']
		if hasattr(body, 'decode'):
			body = body.decode('utf-8')
		elif not isinstance(body, str):
			body = str(body)
		data = system.util.jsonDecode(body)

		# jsonDecode returns Java types; convert to Python dict
		data = dict(data)

		provider = data.get('provider', 'Timescale historian')
		tagProvider = data.get('tagProvider', 'default')
		paths = list(data['paths'])
		values = [list(v) for v in data['values']]
		timestamps_ms = list(data['timestamps'])
		qualities = list(data['qualities']) if 'qualities' in data else [192] * len(timestamps_ms)

		dates = [system.date.fromMillis(long(ts)) for ts in timestamps_ms]

		# qualities must be list of lists (one per path)
		qual_lists = [qualities[:] for _ in paths]

		system.tag.storeTagHistory(
			historyprovider=provider,
			tagprovider=tagProvider,
			paths=paths,
			values=values,
			qualities=qual_lists,
			timestamps=dates
		)

		return {'json': {'success': True, 'pointCount': len(paths) * len(timestamps_ms)}}

	except:
		import traceback
		return {'json': {'success': False, 'error': traceback.format_exc()}}