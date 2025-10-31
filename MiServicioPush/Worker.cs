using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using System.Text.Json;
using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;

namespace MiServicioPush;

public class Worker : BackgroundService
{
    private readonly ILogger<Worker> _logger;
    private readonly IConfiguration _configuration;
    private Timer? _timerPush;
    private Timer? _timerActualizarIntervalo;
    private int _intervaloSegundos = 30; // Valor por defecto (5 min)
    private readonly string _tokenDispositivoAndroid; // Token FCM del dispositivo Android (obténlo de tu app)

    public Worker(ILogger<Worker> logger, IConfiguration configuration)
    {
        _logger = logger;
        _configuration = configuration;
        _tokenDispositivoAndroid = ""; // Reemplaza con el token real de tu app Android
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Inicializa Firebase
        var serviceAccount = GoogleCredential.FromFile(_configuration["Firebase:ServiceAccountPath"]);
        FirebaseApp.Create(new AppOptions()
        {
            Credential = serviceAccount
        });

        // Consulta inicial a la DB
        await ActualizarIntervaloDesdeDB();

        // Timer para pushes cada 'time' segundos
        _timerPush = new Timer(EnviarPush, null, TimeSpan.Zero, TimeSpan.FromSeconds(_intervaloSegundos));
        Console.WriteLine("Timer de push iniciado con intervalo de {0} segundos", _intervaloSegundos);
        // Timer para re-consultar DB cada 5 min
        _timerActualizarIntervalo = new Timer(async _ => await ActualizarIntervaloDesdeDB(), null, TimeSpan.FromMinutes(5), TimeSpan.FromMinutes(5));

        _logger.LogInformation("Servicio iniciado. Intervalo actual: {_intervaloSegundos} segundos", _intervaloSegundos);
    }

    private async Task ActualizarIntervaloDesdeDB()
    {
        try
        {
            using var connection = new SqlConnection(_configuration.GetConnectionString("DefaultConnection"));
            await connection.OpenAsync();

            using var command = new SqlCommand("dbo.GetTimeJson", connection);
            command.CommandType = System.Data.CommandType.StoredProcedure;

            var jsonResult = await command.ExecuteScalarAsync();
            if (jsonResult != null)
            {
                var jsonDoc = JsonDocument.Parse(jsonResult.ToString());
                if (jsonDoc.RootElement.TryGetProperty("time", out var timeElement))
                {
                    _intervaloSegundos = timeElement.GetInt32();
                    _logger.LogInformation("Intervalo actualizado desde DB: {_intervaloSegundos} segundos", _intervaloSegundos);

                    // Reinicia el timer de push con nuevo intervalo
                    _timerPush?.Change(TimeSpan.Zero, TimeSpan.FromSeconds(_intervaloSegundos));
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error al consultar DB para intervalo");
        }
    }

    private void EnviarPush(object? state)
    {
        try
        {
            var message = new Message()
            {
                Token = _tokenDispositivoAndroid,
                Data = new Dictionary<string, string>()
                {
                    { "command", "ejecutar_tarea" },  // La "orden" para Android
                    { "timestamp", DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss") }
                },
   
            Android = new AndroidConfig()  // NUEVO
                {
                    Priority = Priority.High,  
                }
            };

            FirebaseMessaging.DefaultInstance.SendAsync(message).Wait(); // Envía push
            _logger.LogInformation("Push enviado a Android");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error al enviar push");
        }
    }

    public override void Dispose()
    {
        _timerPush?.Dispose();
        _timerActualizarIntervalo?.Dispose();
        base.Dispose();
    }
}