package il.co.dsp211;

import j2html.tags.ContainerTag;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static j2html.TagCreator.*;

/**
 * <ul>
 *     <li>The manager responsible to determine the manager->workers and workers<-manager queues names</li>
 *     <li>The local app responsible to determine the localApp->manager and manager<-localApp queues names</li>
 * </ul>
 */
public class Main
{
	static
	{
		j2html.Config.indenter = (level, text) -> String.join("", Collections.nCopies(level, "\t")) + text;
	}

	public static void main(String[] args)
	{
		final Map<String /*local app <- manager URL*/, Triple<String /*input/output bucket name*/, Long /*remaining tasks*/, Queue<ContainerTag>>> map = new ConcurrentHashMap<>();

		final EC2Methods ec2Methods = new EC2Methods();
		final SQSMethods sqsMethods = new SQSMethods();
		final S3Methods s3Methods = new S3Methods();

		final var box = new Object()
		{
			boolean isTermination;
		};

		final String
				workerToManagerQueueUrl = sqsMethods.createQueue("workerToManagerQueue"),
				managerToWorkersQueueUrl = sqsMethods.createQueue("managerToWorkersQueue"),
				localAppToManagerQueueUrl = sqsMethods.getQueueUrl("localAppToManagerQueue");

//			new task🤠<manager to local app queue url>🤠<input/output bucket name>🤠< URLs file name>🤠<n>[🤠terminate] (local->manager)
//			new image task🤠<manager to local app queue url>🤠<image url> (manager->worker)
//			done OCR task🤠<manager to local app queue url>🤠<image url>🤠<text> (worker->manager)
//			done task🤠<output file name> (manager->local)

		new Thread(() ->
		{
			try (ec2Methods; sqsMethods; s3Methods)
			{
				while (!(box.isTermination && map.isEmpty()))
					sqsMethods.receiveMessage(workerToManagerQueueUrl).stream()
							.map(message -> message.body().split(SQSMethods.getSPLITERATOR())/*gives string array with length 4*/)
							.peek(strings ->
							{
								final Triple<String, Long, Queue<ContainerTag>> value = map.get(strings[1]/*queue url*/);
								--value.t2;
								value.getT3().add(
										p(
												img().withSrc(strings[2]/*image url*/),
												br(),
												text(strings[3]/*text*/)
										)
								);
							})
							.filter(strings -> map.get(strings[1]/*queue url*/).getT2() == 0)
							.forEach(strings ->
							{
								final String outputHTMLFileName = "text.images"+ System.currentTimeMillis() + ".html";
								final Triple<String, Long, Queue<ContainerTag>> data = map.get(strings[1]/*queue url*/);
								s3Methods.uploadStringToS3Bucket(data.getT1(), outputHTMLFileName,
										html(
												head(title("OCR")),
												body(data.getT3()
														.toArray(ContainerTag[]::new))
										).renderFormatted()
								);
								sqsMethods.sendSingleMessage(strings[1]/*queue url*/, "done task" + SQSMethods.getSPLITERATOR() + outputHTMLFileName);
								map.remove(strings[1]/*queue url*/);
							});
				ec2Methods.terminateInstancesByJob(EC2Methods.Job.WORKER);
				sqsMethods.deleteQueue(managerToWorkersQueueUrl);
				sqsMethods.deleteQueue(workerToManagerQueueUrl);
				ec2Methods.terminateInstancesByJob(EC2Methods.Job.MANAGER); //גול עצמי
				System.out.println("Cleaning resources...");
			}
			System.out.println("Exiting ManagerToWorkerThread and JVM process...");
		}, "ManagerToWorkerThread").start();

		while (!box.isTermination)
			sqsMethods.receiveMessage(localAppToManagerQueueUrl).stream()
					.map(message -> message.body().split(SQSMethods.getSPLITERATOR())/*gives string array with length 5/6*/)
					.forEach(strings ->
					{
						box.isTermination = box.isTermination || (strings.length == 6 && strings[5].equals("terminate"));

						ec2Methods.findOrCreateInstancesByJob(args[0]/*worker AMI*/, Integer.parseInt(strings[4]/*n*/), EC2Methods.Job.WORKER, "user data"/*TODO user data*/);

						try (BufferedReader links = s3Methods.readObjectToString(strings[2]/*input/output bucket name*/, strings[3]/*URLs file name*/))
						{
							map.put(strings[1]/*queue url*/,
									new Triple<>(strings[2]/*input/output bucket name*/,
											links.lines()
													.peek(imageUrl -> sqsMethods.sendSingleMessage(managerToWorkersQueueUrl,
															"new image task" + SQSMethods.getSPLITERATOR() + strings[1]/*queue url*/ + SQSMethods.getSPLITERATOR() + imageUrl))
													.count(),
											new LinkedList<>()));
						}
						catch (IOException e)
						{
							e.printStackTrace();
						}
					});
		sqsMethods.deleteQueue(localAppToManagerQueueUrl);
		System.out.println("Exiting main thread...");
	}

	private static class Triple<T1, T2, T3>
	{
		private T1 t1;
		private T2 t2;
		private T3 t3;

		public Triple(T1 t1, T2 t2, T3 t3)
		{
			this.t1 = t1;
			this.t2 = t2;
			this.t3 = t3;
		}

		public T1 getT1()
		{
			return t1;
		}

		public void setT1(T1 t1)
		{
			this.t1 = t1;
		}

		public T2 getT2()
		{
			return t2;
		}

		public void setT2(T2 t2)
		{
			this.t2 = t2;
		}

		public T3 getT3()
		{
			return t3;
		}

		public void setT3(T3 t3)
		{
			this.t3 = t3;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o)
				return true;
			if (!(o instanceof Triple))
				return false;
			Triple<?, ?, ?> triple = (Triple<?, ?, ?>) o;
			return t1.equals(triple.t1) &&
			       t2.equals(triple.t2) &&
			       t3.equals(triple.t3);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(t1, t2, t3);
		}

		@Override
		public String toString()
		{
			return "Pair{" +
			       "t1=" + t1 +
			       ", t2=" + t2 +
			       ", t3=" + t3 +
			       '}';
		}
	}
}
