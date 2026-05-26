"""
genera la grafica de drop rate comparativo entre broker monohilo y multihilo
drop rate = mensajes perdidos / mensajes generados * 100
total esperado en estres (2x5s): 60 sensores * 24 mensajes = 1440 mensajes
"""

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

# datos del benchmark (resultados_benchmark.csv)
# total esperado en escenario estres: 60 hilos * 24 mensajes = 1440
TOTAL_ESPERADO_ESTRES = 1440
# en escenario base: 30 sensores * 12 mensajes = 360
TOTAL_ESPERADO_BASE = 360

# inserts en replica (la mas completa, recibe todo desde analitica)
replica_monohilo_base = 360
replica_monohilo_estres = 1232
replica_multihilo_base = 360
replica_multihilo_estres = 1272

# calculo drop rate = (esperado - persistido) / esperado * 100
drop_mono_base = (TOTAL_ESPERADO_BASE - replica_monohilo_base) / TOTAL_ESPERADO_BASE * 100
drop_mono_estres = (TOTAL_ESPERADO_ESTRES - replica_monohilo_estres) / TOTAL_ESPERADO_ESTRES * 100
drop_multi_base = (TOTAL_ESPERADO_BASE - replica_multihilo_base) / TOTAL_ESPERADO_BASE * 100
drop_multi_estres = (TOTAL_ESPERADO_ESTRES - replica_multihilo_estres) / TOTAL_ESPERADO_ESTRES * 100

escenarios = ['Carga Base\n(1x10s)', 'Carga de Estrés\n(2x5s)']
mono_drops = [drop_mono_base, drop_mono_estres]
multi_drops = [drop_multi_base, drop_multi_estres]

print("=== drop rates calculados ===")
print(f"monohilo base:   {drop_mono_base:.2f}%")
print(f"monohilo estres: {drop_mono_estres:.2f}%")
print(f"multihilo base:  {drop_multi_base:.2f}%")
print(f"multihilo estres:{drop_multi_estres:.2f}%")

# grafica
fig, ax = plt.subplots(figsize=(8, 5))

x = np.arange(len(escenarios))
ancho = 0.32

barras_mono = ax.bar(x - ancho/2, mono_drops, ancho,
                     label='Broker Mono-hilo',
                     color='#E05C5C', edgecolor='#8B0000', linewidth=1.2,
                     zorder=3)
barras_multi = ax.bar(x + ancho/2, multi_drops, ancho,
                      label='Broker Multi-hilo',
                      color='#4C9BE8', edgecolor='#1a4a7a', linewidth=1.2,
                      zorder=3)

# etiquetas sobre las barras
for barra, val in zip(barras_mono, mono_drops):
    ax.text(barra.get_x() + barra.get_width() / 2, barra.get_height() + 0.2,
            f'{val:.1f}%', ha='center', va='bottom', fontsize=11,
            fontweight='bold', color='#8B0000')

for barra, val in zip(barras_multi, multi_drops):
    ax.text(barra.get_x() + barra.get_width() / 2, barra.get_height() + 0.2,
            f'{val:.1f}%', ha='center', va='bottom', fontsize=11,
            fontweight='bold', color='#1a4a7a')

# linea horizontal de referencia en 0
ax.axhline(y=0, color='black', linewidth=0.8, linestyle='-')

ax.set_xlabel('Escenario de Carga', fontsize=12, labelpad=8)
ax.set_ylabel('Tasa de Pérdida de Paquetes (%)', fontsize=12, labelpad=8)
ax.set_title('Drop Rate Comparativo: Broker Mono-hilo vs Multi-hilo', fontsize=13, fontweight='bold', pad=12)
ax.set_xticks(x)
ax.set_xticklabels(escenarios, fontsize=11)
ax.set_ylim(-0.5, max(drop_mono_estres, drop_multi_estres) * 1.35)
ax.legend(fontsize=11, loc='upper left')
ax.grid(axis='y', linestyle='--', alpha=0.5, zorder=0)
ax.set_facecolor('#f9f9f9')
fig.patch.set_facecolor('white')

# anotacion de diferencia en escenario estres
diff = drop_mono_estres - drop_multi_estres
ax.annotate(f'Δ = {diff:.1f}% mejor\ncon Multi-hilo',
            xy=(1 + ancho/2, drop_multi_estres),
            xytext=(1 + ancho/2 + 0.38, drop_multi_estres + 1.5),
            fontsize=9.5, color='#1a4a7a',
            arrowprops=dict(arrowstyle='->', color='#1a4a7a', lw=1.4))

plt.tight_layout()
plt.savefig('drop_rate_comparativo.png', dpi=150, bbox_inches='tight')
plt.close()
print("grafica guardada: drop_rate_comparativo.png")
