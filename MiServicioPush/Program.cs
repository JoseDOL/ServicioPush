using MiServicioPush;

using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

var builder = Host.CreateApplicationBuilder(args);

// Agrega soporte para Windows Services
builder.Services.AddHostedService<Worker>();
builder.Services.AddWindowsService(options =>
{
    options.ServiceName = "MiServicioPush";
});

// Configuración de logging
builder.Services.AddLogging();

var host = builder.Build();
host.Run();
