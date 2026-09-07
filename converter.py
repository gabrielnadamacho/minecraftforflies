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
    except Exception:
        pass

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
        activations = np.zeros(n_neurons)

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

        indices_visuais_matriz = [neuron_to_idx[nid] for nid in ids_visuais if nid in neuron_to_idx]
        activations = np.zeros(n_neurons)

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
            blocks = state.get('blocks', [])
            threats = state.get('threats', [])
            friends = state.get('friends', [])

            # --- PROCESSAMENTO NEURAL BIOLÓGICO & RUÍDO NEURAL ---
            stimulus = 0.1
            if hurt:
                stimulus += 0.9
            if threats:
                stimulus += 0.7
            if friends:
                stimulus -= 0.1

            # PUNIÇÃO POR RUÍDO NEURAL: Se tentar voar, injetamos ruído estocástico de alta amplitude diretamente nos neurônios!
            if flight_attempt:
                noise_punishment = np.random.normal(loc=0.0, scale=3.5, size=n_neurons)
                activations += noise_punishment
                neural_noise_active = True
            else:
                neural_noise_active = False

            if indices_visuais_matriz:
                stim_values = np.random.uniform(0.05, stimulus, len(indices_visuais_matriz))
                activations[indices_visuais_matriz] += stim_values

            if has_connectome:
                activations = W_matrix.dot(activations) + np.tanh(activations)
            else:
                activations = np.tanh(activations + stimulus)
            activations = np.clip(activations, -1.0, 1.0)

            brain_activity = np.mean(np.abs(activations))

            # --- COMPORTAMENTO DA DROSOPHILA ---
            forward = 0.0
            strafe = 0.0
            jump = False
            attack = False
            yaw_delta = 0.0
            thoughts = ""
            action_desc = ""

            if neural_noise_active or flight_attempt:
                thoughts = "⚠️ RUÍDO NEURAL DETECTADO! Tentei levantar voo e meus neurônios sofreram perturbação aversiva. Devo manter os 6 pezinhos firmes no chão!"
                action_desc = "Castigo por tentativa de voo (Ruído Neural Aplicado)"
                forward = -0.6 # Recuar imediatamente
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
                    forward = 0.8
                    attack = True
                else:
                    thoughts = f"👁️ Detecção de ameaça ({mob_name}). Desviando para longe do predador."
                    action_desc = f"Desviando de {mob_name}"
                    forward = -0.8
                    yaw_delta = 110.0
            elif friends:
                friend_name = friends[0]
                thoughts = f"💚 Reconheço um player real ({friend_name}). Eles são amigos! Explorando pacificamente por perto."
                action_desc = f"Acompanhando o player amigo {friend_name}"
                forward = 0.5
            else:
                # Análise dos blocos locais via dados diretos
                block_names = [b.get('name', 'ar') for b in blocks if b.get('solid') == 'true']
                sample_block = block_names[0] if block_names else "ar"

                if brain_activity < 0.2:
                    thoughts = f"💤 Substrato estável ({sample_block}) nas coordenadas ({x:.1f}, {y:.1f}, {z:.1f}). Repousando asas."
                    action_desc = "Repouso no substrato"
                    forward = 0.0
                elif brain_activity < 0.5:
                    thoughts = f"🌿 Caminhando sobre {sample_block} em ({x:.1f}, {y:.1f}, {z:.1f}). Mapeando cheiros e texturas."
                    action_desc = "Caminhando no solo"
                    forward = 0.7
                else:
                    thoughts = f"🔍 Alta atividade sensorial sobre {sample_block}. Girando para inspecionar o ambiente."
                    action_desc = "Inspecionando o ambiente"
                    yaw_delta = 35.0

            send_action(forward, strafe, jump, attack, yaw_delta, 0.0, thoughts)

            if step_count % 10 == 0:
                print(f"\n[Passo {step_count:4d}] Pos:({x:.1f},{y:.1f},{z:.1f}) | Atividade Neural: {brain_activity:.3f} | Ruído Aversivo: {neural_noise_active}")
                print(f"  🧠 [Cérebro de Drosophila] {thoughts}")
                print(f"  ⚡ [Ação Motora] {action_desc}")

            step_count += 1
            time.sleep(0.09)

    except KeyboardInterrupt:
        print("\n\n[!] Interrupção manual do experimento.")
    finally:
        print("[*] Desligando interface neural da Drosophila com segurança...")
        send_action(0, 0, False, False, 0, 0, "Drosophila em repouso final.")

if __name__ == "__main__":
    main()
