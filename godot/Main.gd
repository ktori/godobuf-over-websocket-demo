extends Node2D

const GameProto = preload("res://game_proto.gd")

# Поле ввода сообщения
onready var message_input = $Controls/Message/Input
# Поле ввода имени
onready var name_input = $Controls/Name/Input
# Контейнер для сообщений
onready var message_container = $Messages/ScrollContainer/VBoxContainer

# Хранит ID текущего клиента
var own_id: int
# Хранит пары ID <> Имя
var names = Dictionary()

# WebSocket клиент (удивительно)
var ws: WebSocketClient

# Выводит сообщение на сцену
func show_message(text: String):
	var label = Label.new()
	label.text = text
	label.autowrap = true
	message_container.add_child(label)

# Вызывается при загрузке сцены
func _ready():
	# Создаем WebSocketClient и подключаем обработчики событий
	ws = WebSocketClient.new()
	ws.connect("connection_established", self, "_on_ws_connection_established")
	ws.connect("data_received", self, "_on_ws_data_received")
	ws.connect_to_url("ws://127.0.0.1:8080")

# Вызывается часто по интервалу
func _process(_delta):
	# Производит чтение из вебсокета, читает входящие сообщения
	ws.poll()

# Будет вызываться при установке соединения
func _on_ws_connection_established(_protocol):
	show_message("Connection established!")

# Будет вызываться при получении сообщений из вебсокета
func _on_ws_data_received():
	# Обработка каждого пакета в очереди
	for i in range(ws.get_peer(1).get_available_packet_count()):
		# Сырые данные из пакета
		var bytes = ws.get_peer(1).get_packet()
		var sv_msg = GameProto.SvMessage.new()
		# Превращение массива байтов в структурированное сообщение
		sv_msg.from_bytes(bytes)
		# Обрабатываем уже сконвертированное сообщение
		_on_proto_msg_received(sv_msg)

# Будет вызываться после чтения и конвертации сообщения из вебсокета
func _on_proto_msg_received(msg: GameProto.SvMessage):
	# т.к. все эти поля находятся в блоке oneof - заполнено может быть только
	# одно из них
	if msg.has_connected():
		var c = msg.get_connected()
		own_id = c.get_id()
		name_input.text = c.get_name()
		show_message("Welcome! Your ID is %d and your assigned name is '%s'." % [c.get_id(), c.get_name()])
	elif msg.has_client_connected():
		var cc = msg.get_client_connected()
		names[cc.get_id()] = cc.get_name()
		show_message("%s (%d) connected" % [cc.get_name(), cc.get_id()])
	elif msg.has_client_disconnected():
		var cd = msg.get_client_disconnected()
		show_message("%s (%d) disconnected" % [names[cd.get_id()], cd.get_id()])
		names.erase(cd.get_id())
	elif msg.has_chat_message():
		var cm = msg.get_chat_message()
		show_message("%s: %s" % [names[cm.get_from()], cm.get_text()])
	elif msg.has_name_changed():
		var nc = msg.get_name_changed()
		show_message("%s changed name to %s" % [names[nc.get_id()], nc.get_name()])
		names[nc.get_id()] = nc.get_name()
		if nc.get_id() == own_id:
			name_input.text = nc.get_name()
	elif msg.has_result():
		var result = msg.get_result()
		if result.has_set_name():
			var sn = result.get_set_name()
			if sn.get_success():
				show_message("You have changed your name successfully")
			else:
				show_message("Failed to change name")
				name_input.text = names[own_id]
		else:
			push_warning("Received unknown result: %s" % result.to_string())
	else:
		push_warning("Received unknown message: %s" % msg.to_string())

# Отправляет ClMessage на сервер
func send_msg(msg: GameProto.ClMessage):
	# Конвертируем ClMessage в PoolByteArray и отправляем его по соединению ws
	ws.get_peer(1).put_packet(msg.to_bytes())

# Отправляем сообщение из $Message и очищаем поле
func _on_Send_pressed():
	var msg = GameProto.ClMessage.new()
	var scm = msg.new_send_chat_message()
	scm.set_text(message_input.text)
	message_input.clear()
	send_msg(msg)

# Изменяем имя на введённое в $Name
func _on_Rename_pressed():
	var msg = GameProto.ClMessage.new()
	var sn = msg.new_set_name()
	sn.set_name(name_input.text)
	send_msg(msg)
