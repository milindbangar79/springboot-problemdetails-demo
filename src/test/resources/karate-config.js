function fn() {
  var port = karate.properties['server.port'] || '8080';
  karate.configure('connectTimeout', 5000);
  karate.configure('readTimeout', 10000);
  return {
    baseUrl: 'http://localhost:' + port + '/api'
  };
}
