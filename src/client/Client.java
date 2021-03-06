package client;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.AccessException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import shared.ServerInterface;

public class Client {

  // Type de sécurité pour notre Répartiteur : 0 = sécurisé / 1 = non sécurisé.
  private int _mode;

  // Liste contenant les opérations à répartir entre nos différents clients.
  private ArrayList<Operation> operations_stack;

  // Liste contenant les opérations en cours de traitement par nos serveurs.
  private List<Operation> in_progress_operations_stack;

  // Hashmap contenant chaque threads (1 par serveur) avec la capacité associée
  private HashMap<RepartiteurThread, Integer> threads;

  // HashMap contenant chaque thread et sa liste d'opération en cours.
  private HashMap<RepartiteurThread, ArrayList<Operation>> thread_op;

  // Résultat final de notre fichier d'opérations.
  private int result;

	public static void main(String[] args)
  {
    //hashmap contenant les socket serveur et leur capacité.
    HashMap<HashMap<String,Integer>,Integer> servers = new HashMap<HashMap<String,Integer>,Integer>();

    if (args.length != 2 && (Integer.parseInt(args[1]) != 0 || Integer.parseInt(args[1]) != 1)) //Nombre d'arguments incorrect
    {
      System.out.println("Erreur : Nombre d'arguments incorrect \n\tUsage : ./client Fichier  Mode d'execution:(S = 0/NS = 1)\n");
    }
    else //remplissage de la hashmap contenant les informations sur les serveurs
    {
      try (BufferedReader lines = new BufferedReader(new FileReader("src/client/Servers.txt")))
      {
        String line;
        while ((line = lines.readLine()) != null)
        {
          HashMap<String,Integer> socket = new HashMap<String, Integer>();
          socket.put(line.split(":")[0], Integer.parseInt(line.split(":")[1]));
          servers.put(socket,Integer.parseInt(line.split(":")[2]));
        }

      }
      catch (IOException e)
      {
        System.out.println("Erreur: " + e.getMessage());
      }
      //Création de l'objet répartiteur.
      Client client = new Client(servers, args[0], Integer.parseInt(args[1]));
      client.run();
    }
	}

  /**
   * Constructeur du Répartirteur
   * @param  HashMap<HashMap<String, Integer>, Integer>  servers  HashMap des serveurs disponibles ainsi que leur capacité respective.
   * @param  String                  file          Fichier contenant la liste d'opérations à effectuer.
   * @param  int                     mode          Mode de sécurité de notre répartirteur.
   * @return                         Répartirteur.
   */
	public Client(HashMap<HashMap<String, Integer>, Integer> servers, String file, int mode)
  {
    //Initialisation des attributs
		super();
    this._mode = mode; //mode de fonctionnement du serveur : 0=sécurisé; 1=non sécurisé
    this.operations_stack = new ArrayList<Operation>(); //liste contenant les opérations a réaliser
    this.in_progress_operations_stack = Collections.synchronizedList(new ArrayList<Operation>()); //liste contenant les opérations en cours: cette liste est threadsafe
    this.threads = new HashMap<RepartiteurThread, Integer>(); //Hashmap contenant les threads et leur capacité
    this.thread_op = new HashMap<RepartiteurThread,ArrayList<Operation>>(); //Hashmap contenant les threads et leur liste d'opération en cours.
    this.result = 0;


		if (System.getSecurityManager() == null)
		{
			System.setSecurityManager(new SecurityManager());
		}

    //Initialisation de le pile d'opérations à réaliser.
    FileToArray(file);

    //Création des threads
    for (HashMap<String, Integer> socket : servers.keySet())
    {
      RepartiteurThread t = new RepartiteurThread(loadServerStub(socket));
      threads.put(t, servers.get(socket));
      thread_op.put(t, new ArrayList<Operation>());
      t.start();
    }
	}

  /**
   * Méthode principale de notre répartirteur.
   * Dans un premier temps, on va initialiser le temps pour connaitre la durée d'execution de notre programme.
   * Nous allons ensuite rentrer dans la boucle principale d'exécution tant que la pile d'opérations à faire ou celles en cours de traitement n'est pas vide.
   * La première partie de cette boucle consiste en la répartition des taches vers nos threads pour qu'ils communiquent avec les serveurs.
   * La seconde partie de la boucle est le traitement de la boucle des opérations en cours de traitement.
   * Nous regardons si l'état de l'opération est "Solved" puis nous ajoutons le résultat de l'opration au résultat géréral d'exécution.
   * Finalement, une fois sortie de la boucle principale, nous calculons le temps d'exécution du traitement du fichier d'opérations puis nous affichons le résultat général à l'écran.
   * Les threads sont ensuite coupés avant la fin du programme.
   */
	private void run()
  {
    long start = System.nanoTime();
    ArrayList<Operation> task;
    while(!operations_stack.isEmpty() || !in_progress_operations_stack.isEmpty()) // Boucle tant que les deux listes ne sont pas vides : il reste des opérations a faire
    {

      //Boucle d'envoie de tache aux threads
      for (RepartiteurThread thread : threads.keySet())
      {
        if(!thread.getBusy() && !thread.getDown()) //Si le thread n'est pas occupé et n'est pas coupé, on peut lui envoyer des taches
        {
          //ajustement de sa capacité en fonction de la réponse précedente du serveur.
          if(thread.getOverload()) //on diminue sa capacité si il est surchargé
          {
             setCapacity(thread,false);
          }
          else //on augmente la capacité dans le cas contraire.
          {
             setCapacity(thread,true);
          }
          if(!operations_stack.isEmpty()) //si la pile d'operations n'est pas vide on lui en envoie des taches à traiter
          {
            task=thread_op.get(thread);
            task.clear();

            for (int i = 0; i < threads.get(thread); i++) //on lui envoie autant de tâche que sa capacité l'autorise.
            {
              if(!operations_stack.isEmpty())
              {
                 task.add(operations_stack.get(0));
                 in_progress_operations_stack.add(operations_stack.get(0)); //ajout de la tache a la pile d'opération en cours
                 operations_stack.remove(operations_stack.get(0)); //suppression de la tache de la pile d'opération.
              }
            }
            thread.setTask(task);
          }
        }
      }


      //Traitement de la pile d'opération en cours : On vérifie la résolution des opérations en cours de traitement.
      Iterator it = in_progress_operations_stack.iterator();
      while (it.hasNext())
      {
        Operation operation_inprogress =(Operation)it.next();
        if (operation_inprogress.getTreatment()) //L'opération a été traitée par un serveur
        {
          if(operation_inprogress.isSolved()) //Si celle ci est resolue, on ajoute son resultat
          {
            this.result =(this.result+  operation_inprogress.getResult())%4000;
          }
          else //sinon, on la remet dans la pile d'opération a traiter : elle doit etre retraitée.
          {
            operation_inprogress.setTreatment(false);
            operations_stack.add(operation_inprogress);
          }
          it.remove();
        }
      }
    }

    // Affichage du résultat général :
    System.out.println("result:"+this.result);

    //Calcul du temps d'exécution :
    long end = System.nanoTime();
    System.out.println("Temps écoulé appel RMI distant: "
        + ((end - start)*0.000001) + " ms");

    //Extinction des threads.
    for (RepartiteurThread thread : threads.keySet())
    {
     thread.setInprogress(false);
    }
  }

  /**
   * Méthode permettant de récupérer le stub associé à notre serveur.
   * @param HashMap<String, Integer>  socket Ip et port d'écoute du rmiregistry associé au serveur.
   * @return                 Stub associé au serveur.
   */
	private ServerInterface loadServerStub(HashMap<String, Integer> socket)
  {
		ServerInterface stub = null;

		try
		{
			Registry registry = LocateRegistry.getRegistry(socket.keySet().toArray()[0].toString(), socket.get(socket.keySet().toArray()[0]));
			stub = (ServerInterface) registry.lookup("server");
		}
		catch (NotBoundException e)
		{

			System.out.println("Erreur: Le nom '" + e.getMessage()
					+ "' n'est pas défini dans le registre.");
		}
		catch (AccessException e)
		{

			System.out.println("Erreur: " + e.getMessage());
		}
		catch (RemoteException e)
		{

			System.out.println("Erreur: " + e.getMessage());
		}
		return stub;
	}

  /**
   * Méthode qui récupère toutes les opérations du fichier pour les mettre dans la pile adéquate.
   * @param String file fichier à traiter.
   */
  private void FileToArray(String file)
  {
    try (BufferedReader lines = new BufferedReader(new FileReader("Operations/" + file)))
    {
      String line;
      while ((line = lines.readLine()) != null)
      {
        if (this._mode == 0) //mode sécurisé
          this.operations_stack.add(new Operation(line.split(" ")[0],Integer.parseInt(line.split(" ")[1]), 1));
        else if (this._mode == 1) //mode non sécurisé
          this.operations_stack.add(new Operation(line.split(" ")[0],Integer.parseInt(line.split(" ")[1]), 2));
      }
    }
    catch (IOException e)
    {
      System.out.println("Erreur: " + e.getMessage());
    }
  }

  /**
   * Accesseur sur la capacité de chaque serveur.
   * Cette méthode permet de modifier le nombre d'opérations envoyées à un serveur en fonction de sa surcharge
   * @param RepartiteurThread thread thread associé au serveur dont on veut modifier le nombre d'opérations à envoyer
   * @param Boolean           uprate valeur de la modification : true pour augmenter & false pour diminuer
   */
  private void setCapacity(RepartiteurThread thread, Boolean uprate)
  {
    if (uprate) //augmentation du nombre d'operations a envoyer au serveur
    {
      threads.put(thread, threads.get(thread) + 2);
    }
    else //baisse du nombre d'operations a envoyer au serveur
    {
      threads.put(thread, threads.get(thread) - 2);
    }
    thread.setOverload(false);
  }
}
