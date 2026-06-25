import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'analytics_config.dart';
import 'logger_service.dart';

class AnalyticsService {
  AnalyticsService._();

  static AnalyticsService? _instance;
  static AnalyticsService get instance {
    if (_instance == null) {
      throw StateError('AnalyticsService not initialized. Call init() first.');
    }
    return _instance!;
  }

  static const String _prefsKey = 'loveace_analytics_client_id';

  String _clientId = '';
  String? _gradePrefix;
  String? _studentHash;
  String _appVersion = '';
  String _osVersion = '';
  String _deviceModel = '';
  bool _configured = false;
  Dio? _dio;

  static Future<void> init(SharedPreferences prefs, {required String appVersion}) async {
    if (_instance != null) return;

    final service = AnalyticsService._();
    service._appVersion = appVersion;
    service._clientId = prefs.getString(_prefsKey) ?? '';
    if (service._clientId.isEmpty) {
      try {
        service._clientId = await _generateClientId(prefs);
      } catch (e) {
        LoggerService.error('Failed to generate analytics client_id', error: e);
      }
    }
    service._detectEnvironment();

    service._configured = AnalyticsConfig.apiKey.isNotEmpty &&
        AnalyticsConfig.signingSecret.isNotEmpty;

    if (service._configured) {
      service._dio = Dio(BaseOptions(
        baseUrl: AnalyticsConfig.endpoint,
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 10),
        sendTimeout: const Duration(seconds: 10),
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
        },
      ));
    } else {
      LoggerService.warning('Analytics disabled: apiKey or signingSecret not configured');
    }

    _instance = service;
  }

  void setUser(String userId) {
    final prefix = userId.length >= 4 ? userId.substring(0, 4) : null;
    _gradePrefix = (prefix != null && RegExp(r'^\d{4}$').hasMatch(prefix)) ? prefix : null;
    _studentHash = AnalyticsConfig.hashSalt.isNotEmpty
        ? md5.convert(utf8.encode('$userId${AnalyticsConfig.hashSalt}')).toString()
        : null;
  }

  void clearUser() {
    _gradePrefix = null;
    _studentHash = null;
  }

  void trackAppStart(String launchSource) {
    _track('app_start', {'launch_source': launchSource});
  }

  void trackLoginSuccess(String userId) {
    setUser(userId);
    _track('login_success');
  }

  void trackLoginFailed(String userId, String reason) {
    if (userId.isNotEmpty) setUser(userId);
    _track('login_failed', {'reason': reason});
  }

  void trackSessionExpired(String reason) {
    _track('session_expired', {'reason': reason});
  }

  void trackSessionReconnectSuccess() {
    _track('session_reconnect_success', {'result': 'success'});
  }

  void trackSessionReconnectFailed() {
    _track('session_reconnect_failed', {'result': 'failed'});
  }

  void trackScreen(String screen) {
    _track('screen_view', {'screen': screen});
  }

  void trackFeature(String feature, [String action = 'open']) {
    _track('feature_action', {'feature': feature, 'action': action});
  }

  void trackOtaCheck(String result, String currentVersion, {String? latestVersion}) {
    final props = <String, dynamic>{
      'result': result,
      'current_version': currentVersion,
    };
    if (latestVersion != null) props['latest_version'] = latestVersion;
    _track('ota_check', props);
  }

  void trackOtaUpdateClick(String currentVersion, String targetVersion) {
    _track('ota_update_click', {
      'current_version': currentVersion,
      'target_version': targetVersion,
    });
  }

  void _track(String name, [Map<String, dynamic> properties = const <String, dynamic>{}]) {
    if (!_configured) return;

    final event = <String, dynamic>{
      'name': name,
      'time': DateTime.now().toUtc().toIso8601String(),
      'properties': properties,
    };

    final payload = <String, dynamic>{
      'client_id': _clientId,
      'platform': _platform,
      'app_version': _appVersion,
      'os_version': _osVersion,
      'device_model': _deviceModel,
      'grade_prefix': _gradePrefix,
      'student_hash': _studentHash,
      'events': [event],
    };

    _send(payload);
  }

  void _send(Map<String, dynamic> payload) {
    final dio = _dio;
    if (dio == null) return;

    try {
      final body = jsonEncode(payload);
      final timestamp = (DateTime.now().millisecondsSinceEpoch ~/ 1000).toString();
      final nonce = _generateNonce();
      final bodyHash = sha256.convert(utf8.encode(body)).toString();
      final signature = _hmacSha256(AnalyticsConfig.signingSecret, '$timestamp.$nonce.$bodyHash');

      unawaited(dio.post(
        '',
        data: body,
        options: Options(
          headers: {
            'Authorization': 'Bearer ${AnalyticsConfig.apiKey}',
            'X-LoveACE-Timestamp': timestamp,
            'X-LoveACE-Nonce': nonce,
            'X-LoveACE-Signature': signature,
          },
        ),
      ).catchError((e) {
        if (e is DioException) {
          LoggerService.debug('Analytics event dropped: status=${e.response?.statusCode} type=${e.type} message=${e.message}');
        } else {
          LoggerService.debug('Analytics event dropped: $e');
        }
      }));
    } catch (e) {
      LoggerService.debug('Analytics send failed: $e');
    }
  }

  void _detectEnvironment() {
    try {
      _osVersion = Platform.operatingSystemVersion;
    } catch (_) {
      _osVersion = '';
    }
    _deviceModel = _platform;
  }

  static String get _platform {
    try {
      if (Platform.isWindows) return 'windows';
      if (Platform.isMacOS) return 'macos';
      if (Platform.isLinux) return 'linux';
    } catch (_) {}
    return 'desktop';
  }

  static Future<String> _generateClientId(SharedPreferences prefs) async {
    final id = '${DateTime.now().millisecondsSinceEpoch}-${_generateNonce()}';
    await prefs.setString(_prefsKey, id);
    return id;
  }

  static String _generateNonce() {
    const chars = '0123456789abcdef';
    final random = Random.secure();
    return List.generate(32, (_) => chars[random.nextInt(chars.length)]).join();
  }

  static String _hmacSha256(String secret, String message) {
    final hmac = Hmac(sha256, utf8.encode(secret));
    final digest = hmac.convert(utf8.encode(message));
    return digest.toString();
  }
}
