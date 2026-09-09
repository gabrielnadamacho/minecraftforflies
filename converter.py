import time
import urllib.request
import json
import numpy as np
import pandas as pd
from scipy.sparse import csr_matrix
import cv2
from PIL import ImageGrab
import os

os.environ["OPENCV_LOG_LEVEL"] = "OFF"

SERVER_URL = "http://localhost:8080"

def get_mod_state():
    try:
        req = urllib.request.Request(f"{SERVER_URL}/state", headers={"User-Agent": "DrosophilaAgent"})
        with urllib.request.urlopen(req, timeout=1.0) as response:
            return json.loads(response.read().decode('utf-8'))
    except Exception:
        return None

def send_action(forward=0.0, strafe=0.0, jump=False, attack=False, yaw_delta=0.0, pitch_delta=0.0, thoughts=""):
    try:
        payload = {
            "forward": forward,
            "strafe": strafe,
            "jump": jump,
            "attack": attack,
            "yaw_delta": yaw_delta,
            "pitch_delta": pitch_delta,
            "thoughts": thoughts
        }
        req = urllib.request.Request(
            f"{SERVER_URL}/action",
            data=json.dumps(payload).encode('utf-8'),
            headers={'Content-Type': 'application/json'}
        )
        with urllib.request.urlopen(req, timeout=0.5) as resp:
            pass
    except Exception as e:
        print(f"[!] Erro de comunicação HTTP: {e}")

def main():
    print("=== Drosophila melanogaster: Conectoma Cerebral Biológico Integrado ao Minecraft 1.20.1 ===")
    print("[Bio] Biologia ativa: Caminhamento terrestre obrigatório. Voo proibido (punição por ruído neural direto). Players reais = amigos. Mobs hostis = ameaças.")

    # 1. Carregar conectoma da mosca
    print("[Bio] Carregando conectoma neural (cell_types, connections, neurons)...")
    try:
        df_types = pd.read_csv('cell_types.csv.gz')
        df_conns = pd.read_csv('connections_filtered.csv.gz')
        df_neuro = pd.read_csv('neurons.csv.gz')
        has_connectome = True
    except Exception as e:
        print(f"[-] Aviso: Arquivos CSV do conectoma não encontrados ({e}). Usando matriz neural sintética de Drosophila.")
        has_connectome = False
        n_neurons = 1500
        indices_visuais_matriz = list(range(150))
        W_matrix = csr_matrix((n_neurons, n_neurons))
        activations = np.zeros(n_neurons, dtype=np.float32)

    if has_connectome:
        mapa_nt = df_neuro.set_index('root_id')['nt_type'].to_dict()
        termos_busca_visuais = ['photoreceptor', 'lamina', 'medulla', 'lobula', 'optic', 'R1', 'R2', 'R3', 'R4', 'R5', 'R6', 'R7', 'R8']
        pattern_visual = '|'.join(termos_busca_visuais)
        mask_v = df_types['primary_type'].str.contains(pattern_visual, case=False, na=False) | \
                 df_types['additional_type(s)'].str.contains(pattern_visual, case=False, na=False)
        ids_visuais = df_types[mask_v]['root_id'].values

        all_neurons = df_neuro['root_id'].unique()
        neuron_to_idx = {nid: i for i, nid in enumerate(all_neurons)}
        n_neurons = len(all_neurons)

        valid_conns = df_conns[df_conns['pre_root_id'].isin(neuron_to_idx) & df_conns['post_root_id'].isin(neuron_to_idx)]
        rows = valid_conns['pre_root_id'].map(neuron_to_idx).values
        cols = valid_conns['post_root_id'].map(neuron_to_idx).values
        weights = valid_conns['syn_count'].astype(float).values

        for idx, row in enumerate(rows):
            pre_id = all_neurons[row]
            if mapa_nt.get(pre_id) == 'gaba':
                weights[idx] = -weights[idx]

        W_matrix = csr_matrix((weights, (rows, cols)), shape=(n_neurons, n_neurons))
        row_sums = np.array(W_matrix.sum(axis=1)).flatten()
        row_sums[row_sums == 0] = 1.0
        W_matrix = W_matrix.multiply(1.0 / row_sums[:, np.newaxis])
        W_matrix = W_matrix.astype(np.float32)  # matvec 2x mais leve, SIMD friendly

        indices_visuais_matriz = [neuron_to_idx[nid] for nid in ids_visuais if nid in neuron_to_idx]
        activations = np.zeros(n_neurons, dtype=np.float32)

    print("[+] Conectado ao servidor do mod na porta 8080. Iniciando comportamento biológico...")
    step_count = 0

    try:
        while True:
            state = get_mod_state()
            if not state:
                print("[-] Aguardando conexão com o mod Minecraft...", end='\r')
                time.sleep(1.0)
                continue

            x = state.get('x', 0)
            y = state.get('y', 0)
            z = state.get('z', 0)
            health = state.get('health', 20)
            hurt = state.get('hurt', False)
            flight_attempt = state.get('flight_attempt', False)
            in_water = state.get('in_water', False)
            blocks = [b for b in (state.get('blocks') or []) if isinstance(b, dict)]
            threats = [str(t) for t in (state.get('threats') or []) if t]
            friends_raw = state.get('friends') or []
            items = [str(i) for i in (state.get('items') or []) if i]

            # --- NORMALIZAÇÃO DOS AMIGOS ---
            # O /state pode vir do mod legado (lista de strings) ou do novo (lista de
            # dicts). Blindamos para nunca derrubar o loop — a mosca é resiliente.
            friends = []
            for fr in friends_raw:
                if isinstance(fr, str):
                    # Mod legado: só temos o nome — assume player parado/presença.
                    friends.append({'name': fr, 'x': x, 'z': z, 'moving': 'false'})
                else:
                    friends.append({
                        'name': str(fr.get('name', 'player')),
                        'x': float(fr.get('x', x)),
                        'z': float(fr.get('z', z)),
                        'moving': str(fr.get('moving', 'false')).lower() == 'true',
                    })

            # --- PROCESSAMENTO NEURAL BIOLÓGICO & RUÍDO NEURAL ---
            stimulus = 0.1
            if hurt:
                stimulus += 0.9
            if threats:
                stimulus += 0.7
            if friends:
                stimulus -= 0.1

            # PUNIÇÃO POR RUÍDO NEURAL: apenas fora d'água. Nadar é permitido (a
            # Drosophila é anfíbia no mod), então em água não aplicamos o ruído
            # aversivo que faria a mosca recuar/flutuar descontrolado.
            if flight_attempt and not in_water:
                noise_punishment = np.random.normal(loc=0.0, scale=3.5, size=n_neurons)
                activations += noise_punishment
                neural_noise_active = True
            else:
                neural_noise_active = False

            if indices_visuais_matriz:
                stim_values = np.random.uniform(0.05, stimulus, len(indices_visuais_matriz))
                activations[indices_visuais_matriz] += stim_values

            if has_connectome:
                d = W_matrix.dot(activations)
                np.tanh(activations, out=activations)
                activations += d
            else:
                activations += stimulus
                np.tanh(activations, out=activations)
            np.clip(activations, -1.0, 1.0, out=activations)

            # brain_activity ≈ max(|x|) sem copia/alloc por tick
            brain_activity = max(float(activations.max()), float(-activations.min()))

            # --- COMPORTAMENTO DA DROSOPHILA ---
            forward = 0.0
            strafe = 0.0
            jump = False
            attack = False
            yaw_delta = 0.0
            thoughts = ""
            action_desc = ""

            if in_water:
                # Natação: sem ruído punitivo. Rema (forward + strafe) e usa o
                # jump para boiar/não afundar. Explora a água como território.
                thoughts = "🌊 Na água! Nadando livre — sem ruído punitivo aqui."
                action_desc = "Nadando"
                forward = 1.0
                strafe = float(np.random.choice([-1.0, 1.0]))
                jump = True  # boia/nada para cima se estiver submerso
                if np.random.random() < 0.3:
                    yaw_delta = float(np.random.choice([-40.0, 40.0]))
            elif neural_noise_active or flight_attempt:
                thoughts = "⚠️ RUÍDO NEURAL DETECTADO! Tentei levantar voo e meus neurônios sofreram perturbação aversiva. Devo manter os 6 pezinhos firmes no chão!"
                action_desc = "Castigo por tentativa de voo (Ruído Neural Aplicado)"
                forward = -1.0 # Recuar imediatamente
                jump = False
            elif hurt:
                thoughts = "💥 Estímulo nociceptivo! Sofri dano físico. Acionando circuito de fuga urgente!"
                action_desc = "Fugindo de dano em zigue-zague"
                forward = 1.0
                strafe = np.random.choice([-1.0, 1.0])
                yaw_delta = 50.0
            elif threats:
                mob_name = threats[0]
                if brain_activity > 0.6:
                    thoughts = f"⚔️ Perigo biológico! Mob hostil detectado ({mob_name}). Atacando com peças bucais/patas."
                    action_desc = f"Lutando contra {mob_name}"
                    forward = 1.0
                    attack = True
                else:
                    # Fecha distância para o golpe conectar (ataque em ~8 blocos).
                    # Se levar dano, 'hurt' (prioridade acima) assume e foge.
                    thoughts = f"⚔️ Ameaça ({mob_name}) detectada. Fechando distância para o golpe."
                    action_desc = f"Avançando em {mob_name}"
                    forward = 1.0
                    attack = True
            elif time_of_day in ("night", "dusk") and isinstance(bed, dict):
                # Repouso biológico: à noite a mosca busca a cama e "dorme". Se
                # levar dano, 'hurt' (prioridade acima) foge — nunca morre dormindo
                # de bobeira. Cama = lar definido por /fly bed.
                bx = float(bed.get('x', x))
                bz = float(bed.get('z', z))
                bed_dist = ((bx - x) ** 2 + (bz - z) ** 2) ** 0.5
                if bed_dist > 3.0:
                    thoughts = f"🌙 Anoiteceu. Voltando para a cama em ({bx:.0f},{bz:.0f}) — {'%.0f' % bed_dist} blocos."
                    action_desc = "Rumo à cama (repouso)"
                    forward = 1.0
                    yaw_delta = float(((np.degrees(np.arctan2(bx - x, bz - z)) % 360) + 360) % 360 - 180) * 0.5
                else:
                    thoughts = "😴 Na cama, dormindo até o amanhecer."
                    action_desc = "Dormindo na cama (repouso)"
                    forward = 0.0
                    yaw_delta = 0.0
            else:
                # --- ESCOLHA DE PLAYER (multi-player) + FOLLOW EM TEMPO REAL ---
                # Mantém ~5 blocos de um player. Se um player se afasta >15 blocos,
                # resposta IMEDIATA (sem cadência de 10 passos) para ao menos entrar
                # no raio de 50. Se vários players, segue o mais perto que está se
                # movendo; senão o mais perto parado. A distância chega do /state.
                valid = [f for f in friends if isinstance(f, dict)]
                target = None
                if valid:
                    def _pdist(f):
                        d = f.get('dist', 0.0)
                        if d is not None and float(d) > 0.0:
                            return float(d)
                        return ((f.get('x', x) - x) ** 2 + (f.get('z', z) - z) ** 2) ** 0.5
                    moving = [f for f in valid if f.get('moving', False)]
                    pool = moving if moving else valid
                    target = min(pool, key=_pdist)

                if target is not None:
                    tname = target.get('name', 'player')
                    tx = float(target.get('x', x))
                    tz = float(target.get('z', z))
                    tdist = _pdist(target) if valid else 0.0
                    yaw_delta = float(((np.degrees(np.arctan2(tx - x, tz - z)) % 360) + 360) % 360 - 180) * 0.5
                    if tdist > 15.0:
                        thoughts = f"🚀 {tname} fugiu para {'%.0f' % tdist} blocos! Resposta imediata — reconectando (alvo: raio 50)."
                        action_desc = f"RESYNC imediato até {tname}"
                        forward = 1.0
                    elif tdist > 5.0:
                        thoughts = f"💚 Seguindo {tname} em {'%.0f' % tdist} blocos (mantendo ~5)."
                        action_desc = f"Segundo o player {tname}"
                        forward = 1.0
                    else:
                        thoughts = f"🌍 Perto de {tname} ({'%.0f' % tdist} blocos). Vigilância e exploração local."
                        action_desc = f"Vigilância perto de {tname}"
                        forward = 0.6
                        if np.random.random() < 0.3:
                            yaw_delta = float(np.random.choice([-25.0, 25.0]))
                elif items:
                    # Pickup navegado: itens agora vêm com {name,x,y,z}. Escolhe o
                    # mais próximo e gira até ele; ao chegar perto, o Java
                    # maybePickupWeapon() aspira a espada (arma) para o main hand.
                    it = min(items, key=lambda i: ((i.get('x', x) - x) ** 2 + (i.get('z', z) - z) ** 2) ** 0.5)
                    iname = it.get('name', 'item')
                    ix = float(it.get('x', x))
                    iz = float(it.get('z', z))
                    thoughts = f"💎 Recurso detectado: {iname}. Girando até ele para recolher."
                    action_desc = f"Coletando {iname}"
                    forward = 1.0
                    yaw_delta = float(((np.degrees(np.arctan2(ix - x, iz - z)) % 360) + 360) % 360 - 180) * 0.5
                else:
                    # Análise dos blocos locais via dados diretos
                    block_names = [b.get('name', 'ar') for b in blocks if b.get('solid') == 'true']
                    sample_block = block_names[0] if block_names else "ar"

                    if brain_activity < 0.4:
                        thoughts = f"🌿 Caminhando sobre {sample_block} em ({x:.1f}, {y:.1f}, {z:.1f}). Mapeando cheiros e texturas."
                        action_desc = "Caminhando no solo"
                        forward = 1.0
                        if np.random.random() < 0.25:
                            # Exploração lateral: desvio orgânico de rota
                            yaw_delta = float(np.random.choice([-30.0, 30.0]))
                    else:
                        thoughts = f"🔍 Alta atividade sensorial sobre {sample_block}. Girando para inspecionar o ambiente."
                        action_desc = "Inspecionando o ambiente"
                        yaw_delta = 35.0
                        forward = 1.0

            send_action(forward, strafe, jump, attack, yaw_delta, 0.0, thoughts)

            if step_count % 10 == 0:
                print(f"\n[Passo {step_count:4d}] Pos:({x:.1f},{y:.1f},{z:.1f}) | Atividade Neural: {brain_activity:.3f} | Ruído Aversivo: {neural_noise_active}")
                print(f"  🧠 [Cérebro de Drosophila] {thoughts}")
                print(f"  ⚡ [Ação Motora] {action_desc}")

            step_count += 1
            time.sleep(0.05)

    except KeyboardInterrupt:
        print("\n\n[!] Interrupção manual do experimento.")
    finally:
        print("[*] Desligando interface neural da Drosophila com segurança...")
        send_action(0, 0, False, False, 0, 0, "Drosophila em repouso final.")

if __name__ == "__main__":
    main()
