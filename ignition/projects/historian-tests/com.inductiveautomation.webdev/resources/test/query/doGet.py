def doGet(request, session):
	try:
		data = system.util.jsonDecode(request['data'])

		paths = data['paths']
		start = system.date.fromMillis(long(data['startDate']))
		end = system.date.fromMillis(long(data['endDate']))

		kwargs = {
			'paths': paths,
			'startDate': start,
			'endDate': end,
		}

		if 'aggregationMode' in data and data['aggregationMode']:
			kwargs['aggregationMode'] = data['aggregationMode']
		if 'returnSize' in data and data['returnSize']:
			kwargs['returnSize'] = int(data['returnSize'])

		dataset = system.tag.queryTagHistory(**kwargs)

		# Serialize dataset to JSON
		columns = [dataset.getColumnName(c) for c in range(dataset.getColumnCount())]
		rows = []
		for r in range(dataset.getRowCount()):
			row = {}
			for c in range(dataset.getColumnCount()):
				val = dataset.getValueAt(r, c)
				if val is None:
					row[columns[c]] = None
				elif hasattr(val, 'getTime'):
					row[columns[c]] = val.getTime()  # Java Date -> epoch millis
				elif isinstance(val, float) and val != val:
					row[columns[c]] = None  # NaN -> null
				else:
					row[columns[c]] = val
			rows.append(row)

		return {'json': {
			'success': True,
			'columns': columns,
			'rowCount': dataset.getRowCount(),
			'rows': rows
		}}

	except Exception, e:
		return {'json': {'success': False, 'error': str(e)}}